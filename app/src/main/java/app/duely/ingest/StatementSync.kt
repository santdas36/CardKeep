package app.duely.ingest

import android.content.Context
import app.duely.DuelyApp
import app.duely.data.Card
import app.duely.data.ImportLog
import app.duely.data.Ledger
import app.duely.data.MailCrypt
import app.duely.domain.Issuers
import app.duely.reminders.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

data class SyncState(val running: Boolean = false, val message: String = "")

/**
 * Mail -> PDF -> text -> parsed statement -> encrypted DB. Shared by the daily worker and the
 * manual "Sync now" button; a mutex keeps them from overlapping.
 */
object StatementSync {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    suspend fun run(context: Context): String = runDetailed(context).message

    /** [retryable]: the run failed on the network or mail server, so trying again later may work. */
    class RunResult(val message: String, val retryable: Boolean)

    suspend fun runDetailed(context: Context): RunResult = mutex.withLock {
        _state.value = SyncState(true, "Connecting…")
        try {
            val result = try {
                RunResult(withContext(Dispatchers.IO) { doRun(context) }, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: javax.mail.AuthenticationFailedException) {
                RunResult("Sign-in failed. Check the email and app password.", false)
            } catch (e: Exception) {
                RunResult(
                    "Sync failed: ${e.javaClass.simpleName}${e.message?.let { " – " + it.take(100) } ?: ""}",
                    e is java.io.IOException || e is javax.mail.MessagingException,
                )
            }
            withContext(NonCancellable) { runCatching { DuelyApp.from(context).repo.ledger.recordSync(result.message) } }
            _state.value = SyncState(false, result.message)
            result
        } finally {
            // Cancelled or failed while recording: never leave the UI stuck on "Connecting…".
            if (_state.value.running) _state.value = SyncState(false, "Sync stopped.")
        }
    }

    /** Connects and reports which folder would be searched. */
    suspend fun test(host: String, port: Int, user: String, password: String): String = withContext(Dispatchers.IO) {
        try {
            "Connected. Searching “${MailClient(host, port, user, password.replace(" ", "")).test()}”."
        } catch (e: javax.mail.AuthenticationFailedException) {
            "Sign-in failed. Gmail needs an app password, not your normal password."
        } catch (e: Exception) {
            "Couldn't connect: ${e.javaClass.simpleName}${e.message?.let { " – " + it.take(100) } ?: ""}"
        }
    }

    private suspend fun doRun(context: Context): String {
        val app = DuelyApp.from(context)
        val ledger = app.repo.ledger
        ledger.ensureSeeded()
        val src = ledger.mailSourceOnce() ?: return "Email sync isn't set up."
        val password = MailCrypt.dec(src.passwordEnc) ?: return "Email sync isn't set up."
        if (src.user.isBlank()) return "Email sync isn't set up."

        val cards = app.repo.activeCards() // archived cards don't receive new statements
        if (cards.isEmpty()) return "Add a card first."
        val issuers = cards.map { Issuers.byName(it.issuer) }.distinct()
        val extra = src.extraSenders.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        // Level 1: exact addresses (built-in, learned from past imports, typed by you). Level 2: bank domains.
        val learned = ledger.statementSenders().filter { s -> issuers.any { iss -> iss.senders.any { s.contains(it) } } || s in extra }
        val exact = (issuers.flatMap { it.statementFrom } + learned + extra.filter { it.contains('@') }).distinct()
        val domains = (issuers.flatMap { it.senders } + extra.filterNot { it.contains('@') }).distinct()
        if (exact.isEmpty() && domains.isEmpty()) return "None of your cards' issuers have known statement senders. Add one under extra senders."

        Pdf.init(context)
        val profile = ledger.profile(src)
        val since = Date(System.currentTimeMillis() - src.lookbackDays * 86_400_000L)

        _state.value = SyncState(true, "Searching mail…")
        val client = MailClient(src.host, src.port, src.user, password)

        var imported = 0
        var review = 0
        var problems = 0
        // PDFs come one at a time; each is processed (and logged) before the next is downloaded.
        val found = client.fetchStatements(since, exact, domains, skip = { key -> ledger.alreadySeen(key) }) { i, total, mail, failure ->
            _state.value = SyncState(true, "Reading statement ${i + 1} of $total…")
            val outcome = if (failure != null) logError(ledger, mail, failure) else processSafely(context, ledger, cards, profile, mail)
            when (outcome) {
                "imported" -> imported++
                "needs_review" -> review++
                "duplicate" -> {}
                else -> problems++
            }
        }

        return buildString {
            if (found == 0) append("No new statements.")
            else {
                append("$imported imported")
                if (review > 0) append(", $review need review")
                if (problems > 0) append(", $problems couldn't be read")
                append('.')
            }
        }
    }

    /** Result of fixing or importing one PDF: the outcome code and a sentence for the UI. */
    data class Outcome(val code: String, val message: String, val statementId: Long? = null, val key: String = "")

    /** Retries a kept PDF with a typed password and/or a chosen card. */
    suspend fun fix(context: Context, key: String, password: String?, cardId: Long?, remember: Boolean): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = DuelyApp.from(context)
            val ledger = app.repo.ledger
            val e = ledger.logEntry(key) ?: return@withContext Outcome("error", "That email isn't in the list any more.")
            val pdf = ledger.pendingPdf(key) ?: return@withContext Outcome("error", "The PDF isn't stored any more. Use Retry failed to fetch it again.")
            Pdf.init(context)
            ledger.ensureSeeded()
            val cards = app.repo.activeCards()
            val mail = MailPdf(e.key, e.msgId, e.subject, e.sender, e.receivedAt, pdf.fileName, pdf.bytes)
            val pw = password?.trim()?.takeIf { it.isNotEmpty() }
            val r = processOne(context, ledger, cards, ledger.profile(ledger.mailSourceOnce()), mail, listOfNotNull(pw), cards.firstOrNull { it.id == cardId })
            if (pw != null && remember && r.code != "locked") ledger.rememberPassword(pw)
            if (cardId != null && r.code in setOf("imported", "needs_review")) ledger.rememberSender(e.sender, cardId)
            r
        }
    }

    /** Imports a PDF picked from Files or shared from another app, through the same pipeline as email. */
    suspend fun importFile(context: Context, fileName: String, bytes: ByteArray): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = DuelyApp.from(context)
            val ledger = app.repo.ledger
            Pdf.init(context)
            ledger.ensureSeeded()
            val cards = app.repo.activeCards()
            if (cards.isEmpty()) return@withContext Outcome("error", "Add a card first.")
            val mail = MailPdf("file:" + Pdf.sha256(bytes), "", fileName, "", System.currentTimeMillis(), fileName, bytes)
            processOne(context, ledger, cards, ledger.profile(ledger.mailSourceOnce()), mail)
        }
    }

    /**
     * [processOne] for the sync loop: a PDF that throws (parser bug, out of memory) is logged as an error so
     * it isn't fetched again on every sync (Retry failed brings it back), and the rest of the batch carries on.
     */
    private suspend fun processSafely(context: Context, ledger: Ledger, cards: List<Card>, profile: PdfProfile, mail: MailPdf): String =
        try {
            processOne(context, ledger, cards, profile, mail).code
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(ledger, mail, "Couldn't read: ${e.javaClass.simpleName}${e.message?.let { " – " + it.take(100) } ?: ""}")
        } catch (e: OutOfMemoryError) {
            logError(ledger, mail, "Too large to read on this phone")
        }

    private suspend fun logError(ledger: Ledger, mail: MailPdf, detail: String): String {
        ledger.log(ImportLog(mail.key, mail.msgId, mail.subject, mail.from, mail.receivedAt, "error", detail))
        return "error"
    }

    private suspend fun processOne(
        context: Context, ledger: Ledger, cards: List<Card>, profile: PdfProfile, mail: MailPdf,
        extraPasswords: List<String> = emptyList(), forceCard: Card? = null,
    ): Outcome {
        suspend fun done(outcome: String, detail: String, message: String, statementId: Long? = null, keep: Boolean = false): Outcome {
            ledger.log(ImportLog(mail.key, mail.msgId, mail.subject, mail.from, mail.receivedAt, outcome, detail, statementId))
            if (keep) ledger.keepPdf(mail.key, mail.fileName, mail.bytes) else ledger.dropPdf(mail.key)
            return Outcome(outcome, message, statementId, mail.key)
        }

        val hash = Pdf.sha256(mail.bytes)
        if (ledger.hashSeen(hash)) return done("duplicate", "Already imported", "Already imported.")

        // A card you picked for this sender before wins over guessing by bank.
        val remembered = if (forceCard == null) ledger.cardForSender(mail.from)?.let { id -> cards.firstOrNull { it.id == id } } else null
        val issuer = (forceCard ?: remembered)?.let { Issuers.byName(it.issuer) } ?: Issuers.bySender(mail.from)
        val issuerCards = forceCard?.let { listOf(it) } ?: cards.filter { issuer != null && it.issuer == issuer.name }.ifEmpty { cards }
        val candidates = extraPasswords + Passwords.candidates(profile, issuerCards.map { it.last4 }.distinct())

        val text = when (val r = Pdf.extract(mail.bytes, candidates)) {
            is Pdf.Result.Text -> r.text
            Pdf.Result.Locked -> return done(
                "locked",
                if (extraPasswords.isEmpty()) "Password-protected. Tap to enter the password." else "That password didn't work.",
                if (extraPasswords.isEmpty()) "It's password-protected." else "That password didn't work.",
                keep = true,
            )
            is Pdf.Result.Failed -> return done("error", r.reason, "Couldn't open the PDF.")
        }
        if (text.isBlank()) return done("unparsed", "No text in the PDF (scanned image?)", "There's no text in this PDF.")

        // Mail date anchors statements that print dates without a year; a picked file has no useful date.
        val received = if (mail.key.startsWith("file:")) null
        else java.time.Instant.ofEpochMilli(mail.receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

        // Parse once to find the card, then again with that card's bank parser if it differs.
        val first = Parsers.forIssuer(issuer?.name ?: "")
        val firstParsed = first.parse(text, received)
        // A last 4 printed on the statement that matches none of your cards means it isn't for any of them.
        val card = forceCard ?: matchCard(issuerCards, cards, firstParsed.last4, text)
            ?: remembered?.takeIf { firstParsed.last4 == null || it.last4 == firstParsed.last4 }
            ?: return done(
                "unmatched",
                "Couldn't tell which ${issuer?.name?.plus(" ") ?: ""}card this is for (last 4 ${firstParsed.last4 ?: "not found"}). Tap to pick it.",
                "Couldn't tell which card this is for.",
                keep = true,
            )
        val parser = Parsers.forIssuer(card.issuer)
        val parsed = if (parser.name == first.name) firstParsed else parser.parse(text, received)

        val saved = ledger.importStatement(
            card, parsed, java.time.Instant.ofEpochMilli(mail.receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay(),
            mail.msgId, hash, parser.name, text,
        )
        val ok = saved.status == Ledger.STATUS_OK
        Notifications.showStatement(context, card, saved)
        return done(
            if (ok) "imported" else "needs_review", if (ok) "${parsed.lines.size} lines" else saved.note,
            if (ok) "Imported to ${card.nickname}." else "Imported to ${card.nickname}, but it needs a look.", saved.id,
        )
    }
    private fun matchCard(cards: List<Card>, allCards: List<Card>, last4: String?, text: String): Card? {
        // The statement names its card: that card or nothing (never a guess that would overwrite another card).
        if (last4 != null) return cards.firstOrNull { it.last4 == last4 } ?: allCards.singleOrNull { it.last4 == last4 }
        val mentioned = cards.filter { c -> Regex("(?<!\\d)${c.last4}(?!\\d)").containsMatchIn(text) }
        if (mentioned.size == 1) return mentioned[0]
        return cards.singleOrNull()
    }
}
