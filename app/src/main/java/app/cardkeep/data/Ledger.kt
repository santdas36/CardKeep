package app.cardkeep.data

import android.util.Base64
import app.cardkeep.ingest.Categorizer
import app.cardkeep.ingest.DefaultCategories
import app.cardkeep.ingest.ParsedStatement
import app.cardkeep.ingest.Parsers
import app.cardkeep.ingest.TxnKind
import app.cardkeep.ingest.PdfProfile
import app.cardkeep.security.Keys
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/** Non-auth key for the mail password and PDF profile: background sync must read them while locked. */
object MailCrypt {
    private fun key() = Keys.getOrCreate("duely_mail_v1", requireAuth = false)
    fun enc(s: String): ByteArray = Keys.encrypt(key(), s.toByteArray())
    fun dec(b: ByteArray?): String? = b?.let { String(Keys.decrypt(key(), it)) }
}

/** Statements, transactions, categories and the mail source. */
class Ledger(private val dao: LedgerDao, private val onCardChanged: suspend (Long) -> Unit) {

    val statements: Flow<List<Statement>> = dao.observeStatements()
    val txns: Flow<List<Txn>> = dao.observeTxns()
    val categories: Flow<List<Category>> = dao.observeCategories()
    val mailSource: Flow<MailSource?> = dao.observeMailSource()
    val importLog: Flow<List<ImportLog>> = dao.observeImportLog()

    fun statement(id: Long) = dao.observeStatement(id)
    fun linesFor(statementId: Long) = dao.observeTxnsFor(statementId)

    suspend fun ensureSeeded() {
        if (dao.categories().isNotEmpty()) return
        val ids = DefaultCategories.categories().associate { it.name to dao.insertCategory(it) }
        DefaultCategories.rules(ids).forEach { dao.insertRule(it) }
    }

    suspend fun categorizer() = Categorizer(dao.rules())

    // ---- Mail source ----

    suspend fun mailSourceOnce() = dao.mailSource()

    suspend fun saveMailSource(user: String, password: String?, host: String, port: Int, extraSenders: String, lookbackDays: Int, profile: PdfProfile?) {
        val old = dao.mailSource() ?: MailSource()
        dao.upsertMailSource(
            MailSource(
                host = host.trim(), port = port, user = user.trim(),
                passwordEnc = password?.takeIf { it.isNotBlank() }?.let { MailCrypt.enc(it.replace(" ", "")) } ?: old.passwordEnc,
                extraSenders = extraSenders.trim(),
                profileEnc = profile?.let { MailCrypt.enc(it.toJson()) } ?: old.profileEnc,
                lookbackDays = lookbackDays, lastSyncAt = old.lastSyncAt, lastResult = old.lastResult,
            ),
        )
    }

    suspend fun recordSync(result: String) {
        val m = dao.mailSource() ?: return
        dao.upsertMailSource(
            MailSource(m.id, m.host, m.port, m.user, m.passwordEnc, m.extraSenders, m.profileEnc, m.lookbackDays, System.currentTimeMillis(), result),
        )
    }

    suspend fun removeMailSource() = dao.deleteMailSource()

    /** Forgets the account and app password but keeps the PDF-password profile (used by manual import too). */
    suspend fun disconnectMail() {
        val m = dao.mailSource() ?: return
        dao.upsertMailSource(MailSource(m.id, m.host, m.port, "", null, m.extraSenders, m.profileEnc, m.lookbackDays, 0, ""))
    }

    fun profile(m: MailSource?): PdfProfile = PdfProfile.fromJson(runCatching { MailCrypt.dec(m?.profileEnc) }.getOrNull())

    // ---- Import log ----

    suspend fun alreadySeen(key: String) = dao.logEntry(key) != null
    suspend fun log(entry: ImportLog) = dao.upsertLog(entry)
    suspend fun retryFailed() = dao.clearRetryableLog()
    suspend fun hashSeen(hash: String) = dao.countByHash(hash) > 0
    suspend fun logEntry(key: String) = dao.logEntry(key)
    suspend fun statementSenders(): List<String> = dao.statementSenders().map { it.trim().lowercase() }.distinct()
    suspend fun dismiss(key: String) { dao.dismissLog(key); dao.dropPdf(key) }

    // ---- Sender -> card, learned from fixes ----

    suspend fun rememberSender(from: String, cardId: Long) {
        senderAddress(from)?.let { dao.upsertSenderCard(SenderCard(it, cardId)) }
    }
    suspend fun cardForSender(from: String): Long? = senderAddress(from)?.let { dao.cardForSender(it) }

    /** "HDFC Bank <statements@hdfcbank.net>" -> "statements@hdfcbank.net". */
    private fun senderAddress(from: String): String? =
        (Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from).trim().lowercase().takeIf { it.contains('@') }

    // ---- PDFs waiting for a password or a card ----

    val pendingKeys: Flow<List<String>> = dao.observePendingKeys()
    suspend fun keepPdf(key: String, fileName: String, bytes: ByteArray) = dao.keepPdf(PendingPdf(key, fileName, bytes))
    suspend fun pendingPdf(key: String) = dao.pendingPdf(key)
    suspend fun dropPdf(key: String) = dao.dropPdf(key)

    /** Saves the PDF-password profile on its own (works without an email account). */
    suspend fun saveProfile(profile: PdfProfile) {
        val m = dao.mailSource() ?: MailSource()
        dao.upsertMailSource(
            MailSource(m.id, m.host, m.port, m.user, m.passwordEnc, m.extraSenders, MailCrypt.enc(profile.toJson()), m.lookbackDays, m.lastSyncAt, m.lastResult),
        )
    }

    /** Remembers a password that unlocked a statement, as a literal pattern. */
    suspend fun rememberPassword(pw: String) {
        val p = profile(dao.mailSource())
        if (pw.isNotBlank() && pw !in p.templates) saveProfile(p.copy(templates = p.templates + pw))
    }

    // ---- Statements ----

    /** Stores a parsed statement and its lines. Returns the new statement id. */
    suspend fun importStatement(
        card: Card, parsed: ParsedStatement, fallbackDate: Long, msgId: String, fileHash: String, parser: String, rawText: String,
    ): Statement {
        val stmtDay = (parsed.statementDate ?: java.time.LocalDate.ofEpochDay(fallbackDate)).toEpochDay()
        val (fixedOpening, limitNote) = limitChange(card.id, stmtDay, parsed)
        val v = Parsers.validate(parsed.copy(openingPaise = fixedOpening))
        val s = Statement(
            cardId = card.id,
            statementEpochDay = stmtDay,
            periodStartEpochDay = parsed.periodStart?.toEpochDay(),
            dueEpochDay = parsed.dueDate?.toEpochDay(),
            totalDuePaise = parsed.totalDuePaise,
            minDuePaise = parsed.minDuePaise,
            openingPaise = fixedOpening,
            debitsPaise = parsed.debits,
            creditsPaise = parsed.credits,
            status = if (v.ok) STATUS_OK else STATUS_REVIEW,
            note = if (v.ok) limitNote.orEmpty() else v.note,
            sourceMsgId = msgId,
            fileHash = fileHash,
            parser = parser,
            rawText = rawText,
            rewardPointsBalance = parsed.rewards?.pointsBalance,
            rewardPointsEarned = parsed.rewards?.pointsEarned,
            cashbackPaise = parsed.rewards?.cashbackPaise,
            rewardUnit = parsed.rewards?.unit,
            creditLimitPaise = parsed.creditLimitPaise,
        )
        // One transaction: a process killed mid-import must not leave a statement without lines (its file
        // hash would then block re-importing it).
        val id = dao.insertStatementWithLines(s, lines(null, card.id, parsed))
        afterStatementChange(card.id)
        return s.copy(id = id)
    }

    /**
     * If the only mismatch is a credit-limit change since the card's previous statement (the opening was
     * worked out against the new limit), correct the opening by that change and say so instead of flagging it.
     * Returns the opening to use and an explanatory note, if any.
     */
    private suspend fun limitChange(cardId: Long, stmtDay: Long, p: ParsedStatement): Pair<Long?, String?> {
        val opening = p.openingPaise ?: return null to null
        val total = p.totalDuePaise ?: return opening to null
        val limit = p.creditLimitPaise ?: return opening to null
        if (Parsers.validate(p).ok) return opening to null
        val prev = dao.statements()
            .filter { it.cardId == cardId && it.statementEpochDay < stmtDay && it.creditLimitPaise != null }
            .maxByOrNull { it.statementEpochDay }?.creditLimitPaise ?: return opening to null
        val change = limit - prev
        if (change == 0L) return opening to null
        val diff = opening + p.debits - p.credits - total
        if (kotlin.math.abs(kotlin.math.abs(diff) - kotlin.math.abs(change)) > 100) return opening to null
        val note = "Credit limit changed from ${app.cardkeep.domain.Money.inr(prev)} to ${app.cardkeep.domain.Money.inr(limit)} this cycle"
        return (opening - diff) to note
    }

    private suspend fun writeLines(statementId: Long, cardId: Long, parsed: ParsedStatement) {
        dao.replaceStatementLines(statementId, lines(statementId, cardId, parsed))
    }

    /** Rows for [parsed]'s lines. [statementId] may be null when the statement isn't inserted yet. */
    private suspend fun lines(statementId: Long?, cardId: Long, parsed: ParsedStatement): List<Txn> {
        val cat = categorizer()
        val seen = HashMap<String, Int>()
        return parsed.lines.map { l ->
            val base = "$cardId|${l.date.toEpochDay()}|${l.amountPaise}|${l.isCredit}|${normal(l.description)}|${l.reference}"
            val n = seen.merge(base, 1, Int::plus)!!
            Txn(
                cardId = cardId, statementId = statementId, epochDay = l.date.toEpochDay(), merchant = l.description,
                amountPaise = l.amountPaise, isCredit = l.isCredit, categoryId = cat.categorise(l.description),
                reference = l.reference, dedupeKey = sha("$base|$n"), kind = TxnKind.classify(l.description, l.isCredit),
            )
        }
    }

    /**
     * After a parser upgrade: statements that needed review are re-parsed in full; ones already OK
     * (possibly hand-edited) only get their reward fields filled in, so no edits are lost.
     */
    suspend fun refreshAfterParserUpgrade(issuerOf: (Long) -> String?) {
        for (s in dao.statements()) {
            val issuer = issuerOf(s.cardId) ?: continue
            if (s.status == STATUS_REVIEW) { reparse(s.id, issuer); continue }
            val p = runCatching { Parsers.forIssuer(issuer).parse(s.rawText) }.getOrNull() ?: continue
            val r = p.rewards
            dao.updateStatement(
                s.copy(
                    rewardPointsBalance = r?.pointsBalance, rewardPointsEarned = r?.pointsEarned, cashbackPaise = r?.cashbackPaise,
                    rewardUnit = r?.unit, creditLimitPaise = p.creditLimitPaise,
                ),
            )
            afterStatementChange(s.cardId)
        }
    }

    /** Re-runs the parser on the stored text (after a parser fix) and replaces the lines. */
    suspend fun reparse(id: Long, issuer: String) {
        val s = dao.statement(id) ?: return
        val parsed = Parsers.forIssuer(issuer).parse(s.rawText)
        val (fixedOpening, limitNote) = limitChange(s.cardId, parsed.statementDate?.toEpochDay() ?: s.statementEpochDay, parsed)
        val p = parsed.copy(openingPaise = fixedOpening)
        val v = Parsers.validate(p)
        dao.updateStatement(
            s.copy(
                periodStartEpochDay = p.periodStart?.toEpochDay() ?: s.periodStartEpochDay,
                statementEpochDay = p.statementDate?.toEpochDay() ?: s.statementEpochDay,
                dueEpochDay = p.dueDate?.toEpochDay(), totalDuePaise = p.totalDuePaise, minDuePaise = p.minDuePaise,
                openingPaise = p.openingPaise, debitsPaise = p.debits, creditsPaise = p.credits,
                status = if (v.ok) STATUS_OK else STATUS_REVIEW, note = if (v.ok) limitNote.orEmpty() else v.note,
                parser = Parsers.forIssuer(issuer).name,
                rewardPointsBalance = p.rewards?.pointsBalance, rewardPointsEarned = p.rewards?.pointsEarned,
                cashbackPaise = p.rewards?.cashbackPaise, rewardUnit = p.rewards?.unit, creditLimitPaise = p.creditLimitPaise,
            ),
        )
        writeLines(id, s.cardId, p)
        afterStatementChange(s.cardId)
    }

    /** Header edits from the review screen. Re-validates against the current lines. */
    suspend fun editStatementHeader(id: Long, due: Long?, total: Long?, min: Long?, opening: Long?) {
        val s = dao.statement(id) ?: return
        dao.updateStatement(s.copy(dueEpochDay = due, totalDuePaise = total, minDuePaise = min, openingPaise = opening))
        revalidate(id)
    }

    suspend fun markReviewed(id: Long) {
        val s = dao.statement(id) ?: return
        dao.updateStatement(s.copy(status = STATUS_OK, note = "Accepted after review"))
        afterStatementChange(s.cardId)
    }

    suspend fun deleteStatement(id: Long) {
        val s = dao.statement(id) ?: return
        dao.deleteStatement(id)
        afterStatementChange(s.cardId)
    }

    private suspend fun revalidate(statementId: Long) {
        val s = dao.statement(statementId) ?: return
        val lines = dao.txnsFor(statementId)
        val debits = lines.filter { !it.isCredit }.sumOf { it.amountPaise }
        val credits = lines.filter { it.isCredit }.sumOf { it.amountPaise }
        val v = Parsers.validate(s.totalDuePaise, s.dueEpochDay != null, s.openingPaise, debits, credits)
        val status = if (v.ok) STATUS_OK else if (s.status == STATUS_OK && s.note == "Accepted after review") STATUS_OK else STATUS_REVIEW
        dao.updateStatement(s.copy(debitsPaise = debits, creditsPaise = credits, status = status, note = if (v.ok) "" else v.note))
        afterStatementChange(s.cardId)
    }

    // ---- Transactions ----

    suspend fun saveLine(t: Txn) {
        if (t.id == 0L) {
            val key = sha("manual|${t.cardId}|${t.epochDay}|${t.amountPaise}|${System.nanoTime()}")
            dao.insertTxn(t.copy(dedupeKey = key, categoryId = t.categoryId ?: categorizer().categorise(t.merchant), kind = TxnKind.classify(t.merchant, t.isCredit)))
        } else dao.updateTxn(if (t.kind == TxnKind.SPEND || t.kind == TxnKind.REFUND) t.copy(kind = TxnKind.classify(t.merchant, t.isCredit)) else t)
        t.statementId?.let { revalidate(it) }
    }

    suspend fun deleteLine(t: Txn) {
        dao.deleteTxn(t.id)
        t.statementId?.let { revalidate(it) }
    }

    /** Sets one transaction's category; with [pattern], also saves a rule and applies it to all matching. */
    suspend fun setCategory(t: Txn, categoryId: Long?, pattern: String?) {
        dao.updateTxn(t.copy(categoryId = categoryId))
        val p = pattern?.trim()?.lowercase()
        if (!p.isNullOrBlank() && categoryId != null) {
            dao.deleteRulesFor(p)
            dao.insertRule(CategoryRule(categoryId = categoryId, pattern = p, priority = DefaultCategories.USER_PRIORITY))
            dao.recategorise(p, categoryId)
            dao.recategoriseSpends(p, categoryId)
        }
    }

    suspend fun addCategory(name: String, color: Long) = dao.insertCategory(Category(name = name.trim(), color = color))

    suspend fun allTxns() = dao.txns()

    /** Fills [Txn.kind] for lines imported before kinds existed, or after the rules improve. */
    suspend fun reclassifyAll() {
        val cat = categorizer()
        for (t in dao.txns()) {
            val merchant = app.cardkeep.ingest.Text.stripEmiLink(t.merchant)
            val k = TxnKind.classify(merchant, t.isCredit)
            if (k != t.kind || merchant != t.merchant) {
                // Re-categorise only lines whose text changed; hand-picked categories otherwise stay.
                val c = if (merchant != t.merchant) cat.categorise(merchant) ?: t.categoryId else t.categoryId
                dao.updateTxn(t.copy(kind = k, merchant = merchant, categoryId = c))
            }
        }
    }

    suspend fun setNote(t: Txn, note: String) = dao.updateTxn(t.copy(note = note.trim()))

    /** Sets a line's kind by hand (e.g. a refund that was read as a payment). */
    suspend fun setKind(t: Txn, kind: String) = dao.updateTxn(t.copy(kind = kind))
    suspend fun allStatements() = dao.statements()

    private suspend fun afterStatementChange(cardId: Long) = onCardChanged(cardId)

    private fun normal(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun sha(s: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()), Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_REVIEW = "needs_review"
    }
}
