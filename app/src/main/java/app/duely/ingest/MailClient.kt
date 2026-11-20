package app.duely.ingest

import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import com.sun.mail.imap.protocol.IMAPResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

/** A PDF attachment from a statement-looking email. */
class MailPdf(
    val key: String,
    val msgId: String,
    val subject: String,
    val from: String,
    val receivedAt: Long,
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * Read-only IMAP over TLS. Opens folders read-only, so nothing is ever marked read or moved.
 *
 * Search is two-level and runs on the server:
 *  1. exact statement sender addresses (built-in list + ones learned from past imports);
 *  2. the banks' domains, but only mails whose subject looks like a statement.
 * On Gmail both use Gmail's own search (X-GM-RAW) with has:attachment filename:pdf, so only statement
 * mails with a PDF come back, not every alert and OTP from the bank. Headers and structure for all hits
 * are fetched in one round trip; only the PDF parts are downloaded.
 */
class MailClient(private val host: String, private val port: Int, private val user: String, private val password: String) {

    private inline fun <T> withStore(block: (Store) -> T): T {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "60000")
            put("mail.imaps.fetchsize", "1048576")
            put("mail.imaps.partialfetch", "false") // fetch each PDF part in one go
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(host, port, user, password)
        try {
            return block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    /** Gmail's "All Mail" (by the \All attribute, so it works in any UI language), else INBOX. */
    private fun allMail(store: Store): Folder =
        store.defaultFolder.list("*").firstOrNull { f -> (f as? IMAPFolder)?.attributes?.any { it.equals("\\All", true) } == true }
            ?: store.getFolder("INBOX")

    /** Returns the folder that will be searched. Throws on bad credentials. */
    fun test(): String = withStore { allMail(it).fullName }

    /**
     * Finds statement PDFs since [since]. [exact] are full sender addresses (level 1); [domains] are
     * domain words for the subject-filtered level 2. [skip] avoids downloading attachments already handled.
     * PDFs are downloaded one at a time and handed to [each] (with its index and the total) before the next,
     * so only one is in memory. If one attachment can't be downloaded (too large, broken encoding) while the
     * connection is still fine, [each] gets it with empty bytes and the reason, and the rest carry on.
     * Returns how many new PDFs were found.
     */
    suspend fun fetchStatements(
        since: Date, exact: List<String>, domains: List<String>,
        skip: suspend (String) -> Boolean,
        each: suspend (index: Int, total: Int, pdf: MailPdf, failure: String?) -> Unit,
    ): Int = withStore { store ->
        val folder = allMail(store) as IMAPFolder
        folder.open(Folder.READ_ONLY)
        try {
            val gmail = (store as? IMAPStore)?.hasCapability("X-GM-EXT-1") == true
            val messages: List<Message> = if (gmail) gmailSearch(folder, since, exact, domains) else imapSearch(folder, since, exact, domains)
            if (messages.isEmpty()) return@withStore 0

            // One round trip for envelope + structure of every hit.
            folder.fetch(
                messages.toTypedArray(),
                FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.CONTENT_INFO); add(UIDFolder.FetchProfileItem.UID) },
            )

            // First pass: what's new, from headers and structure only.
            val hits = mutableListOf<Pair<MailPdf, Part>>()
            for (m in messages) {
                val subject = runCatching { m.subject }.getOrNull().orEmpty()
                val msgId = (m as? MimeMessage)?.messageID ?: "${folder.fullName}:${folder.getUID(m)}"
                val from = (m.from?.firstOrNull() as? InternetAddress)?.address ?: m.from?.firstOrNull()?.toString().orEmpty()
                val received = (m.receivedDate ?: m.sentDate ?: Date()).time
                for ((name, part) in pdfParts(m)) {
                    if (!looksLikeStatement(subject, name)) continue
                    val key = "$msgId#$name"
                    if (skip(key)) continue
                    hits += MailPdf(key, msgId, subject, from, received, name, EMPTY) to part
                }
            }

            // Second pass: download and hand over one at a time.
            for ((i, hit) in hits.withIndex()) {
                val (meta, part) = hit
                var failure: String? = null
                val bytes = try {
                    val size = runCatching { part.size }.getOrDefault(-1)
                    if (size > MAX_PDF_BYTES) { failure = "Attachment too large (${size / 1_048_576} MB)"; EMPTY }
                    else part.inputStream.use { it.readBytes() }
                } catch (e: OutOfMemoryError) {
                    failure = "Attachment too large to read on this phone"; EMPTY
                } catch (e: Exception) {
                    // A dropped connection fails the whole run (and it's retried); a bad attachment is skipped.
                    if (!folder.isOpen || !store.isConnected) throw e
                    failure = "Couldn't download the PDF: ${e.javaClass.simpleName}"; EMPTY
                }
                each(i, hits.size, if (failure == null) MailPdf(meta.key, meta.msgId, meta.subject, meta.from, meta.receivedAt, meta.fileName, bytes) else meta, failure)
            }
            hits.size
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun looksLikeStatement(subject: String, fileName: String) = listOf(subject, fileName).any {
        val s = it.lowercase()
        KEYWORDS.any { k -> s.contains(k) }
    }

    // ---- Gmail: server-side search ----

    private fun gmailSearch(folder: IMAPFolder, since: Date, exact: List<String>, domains: List<String>): List<Message> {
        val after = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(since)
        val base = "has:attachment filename:pdf after:$after"
        val uids = LinkedHashSet<Long>()
        if (exact.isNotEmpty()) {
            uids += rawSearch(folder, "from:(${exact.joinToString(" OR ")}) $base")
        }
        if (domains.isNotEmpty()) {
            val subjects = KEYWORDS.joinToString(" OR ") { "subject:$it" }
            uids += rawSearch(folder, "from:(${domains.joinToString(" OR ")}) ($subjects) $base")
        }
        if (uids.isEmpty()) return emptyList()
        return folder.getMessagesByUID(uids.toLongArray()).filterNotNull()
    }

    /** UID SEARCH X-GM-RAW "<gmail query>" — Gmail's own search syntax, evaluated on the server. */
    private fun rawSearch(folder: IMAPFolder, query: String): List<Long> {
        @Suppress("UNCHECKED_CAST")
        return folder.doCommand { p ->
            val args = Argument().apply { writeString(query) }
            val responses = p.command("UID SEARCH X-GM-RAW", args)
            val found = mutableListOf<Long>()
            for (r in responses) {
                if (r is IMAPResponse && r.keyEquals("SEARCH")) {
                    while (true) {
                        val n = r.readLong()
                        if (n < 0) break
                        found += n
                    }
                }
            }
            p.notifyResponseHandlers(responses)
            p.handleResult(responses.last())
            found
        } as List<Long>
    }

    // ---- Other providers: standard IMAP search (still server-side) ----

    private fun imapSearch(folder: IMAPFolder, since: Date, exact: List<String>, domains: List<String>): List<Message> {
        fun anyOf(terms: List<SearchTerm>): SearchTerm = if (terms.size == 1) terms[0] else OrTerm(terms.toTypedArray())
        val date = ReceivedDateTerm(ComparisonTerm.GE, since)
        val hits = LinkedHashMap<Long, Message>()
        if (exact.isNotEmpty()) {
            folder.search(AndTerm(date, anyOf(exact.map { FromStringTerm(it) }))).forEach { hits[folder.getUID(it)] = it }
        }
        if (domains.isNotEmpty()) {
            val subject = anyOf(KEYWORDS.map { SubjectTerm(it) })
            folder.search(AndTerm(arrayOf(date, anyOf(domains.map { FromStringTerm(it) }), subject))).forEach { hits[folder.getUID(it)] = it }
        }
        return hits.values.toList()
    }

    /** PDF parts, from the already-fetched structure. Never downloads text bodies. */
    private fun pdfParts(p: Part): List<Pair<String, Part>> {
        if (p.isMimeType("multipart/*")) {
            val content = runCatching { p.content }.getOrNull() as? Multipart ?: return emptyList()
            return (0 until content.count).flatMap { pdfParts(content.getBodyPart(it)) }
        }
        val name = runCatching { p.fileName }.getOrNull().orEmpty()
        val isPdf = name.lowercase().endsWith(".pdf") || p.isMimeType("application/pdf")
        return if (isPdf) listOf((name.ifBlank { "statement.pdf" }) to p) else emptyList()
    }

    companion object {
        /** What statement mails say in the subject or attachment name. */
        val KEYWORDS = listOf("statement", "stmt", "e-bill", "ebill", "estatement")

        /** Encoded (base64) size above which an attachment isn't downloaded; statements are a few MB at most. */
        private const val MAX_PDF_BYTES = 40 * 1_048_576
        private val EMPTY = ByteArray(0)
    }
}
