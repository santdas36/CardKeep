package app.duely.data

import androidx.room.withTransaction
import app.duely.security.SecretDraft
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup of everything in the app.
 *
 * File: "CARDSBK1" | iterations (4 bytes) | salt (16) | iv (12) | AES-256-GCM( gzip( JSON ) ).
 * The key comes from the password via PBKDF2-HMAC-SHA256. The header is authenticated as GCM AAD, so
 * tampering with it fails decryption. Card secrets are inside in plain form (the file is the protection)
 * and are re-encrypted under the new phone's own Keystore keys on restore.
 * Not included: the Gmail app password (revocable; paste it again after restoring).
 */
object Backup {
    private val MAGIC = "CARDSBK1".toByteArray()
    private const val ITERATIONS = 300_000
    private const val FORMAT = 1
    private val PENDING_PDF_OUTCOMES = setOf("locked", "unmatched")

    class WrongPassword : Exception("Wrong password, or not a Cards backup")

    /** What a backup holds, shown before restoring. */
    data class Summary(val createdAt: Long, val cards: Int, val statements: Int, val transactions: Int)

    /** Decrypted contents, ready to restore. */
    class Contents internal constructor(internal val json: JSONObject) {
        val summary = Summary(
            json.optLong("createdAt"),
            json.optJSONArray("cards")?.length() ?: 0,
            json.optJSONArray("statements")?.length() ?: 0,
            json.optJSONArray("transactions")?.length() ?: 0,
        )
    }

    // ---------- Crypto ----------

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(plain: ByteArray, password: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val header = MAGIC + int(ITERATIONS) + salt + iv
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        return header + c.doFinal(gzip(plain))
    }

    fun open(file: ByteArray, password: CharArray): ByteArray {
        val headerLen = MAGIC.size + 4 + 16 + 12
        if (file.size <= headerLen || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw WrongPassword()
        val iterations = readInt(file, MAGIC.size)
        if (iterations !in 10_000..5_000_000) throw WrongPassword()
        val salt = file.copyOfRange(MAGIC.size + 4, MAGIC.size + 20)
        val iv = file.copyOfRange(MAGIC.size + 20, headerLen)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            c.updateAAD(file.copyOfRange(0, headerLen))
            gunzip(c.doFinal(file, headerLen, file.size - headerLen))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassword()
        }
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readInt(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray) = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    // ---------- Export ----------

    /** Builds the JSON. [secrets] are the decrypted card secrets by card id (needs an unlocked vault). */
    suspend fun collect(db: AppDatabase, secrets: Map<Long, SecretDraft>, prefs: Prefs): ByteArray {
        val d = db.backup()
        val o = JSONObject()
            .put("format", FORMAT)
            .put("createdAt", System.currentTimeMillis())
            .put("cards", JSONArray(d.cards().map { c ->
                JSONObject().put("id", c.id).put("nickname", c.nickname).put("issuer", c.issuer).put("network", c.network)
                    .put("last4", c.last4).put("limitPaise", c.limitPaise).put("outstandingPaise", c.outstandingPaise)
                    .put("statementDay", c.statementDay).put("dueOffsetDays", c.dueOffsetDays).put("color", c.color)
                    .put("pinned", c.pinned).put("reminderLeads", c.reminderLeads).put("notes", c.notes)
                    .put("createdAt", c.createdAt).putOpt("archivedAt", c.archivedAt).put("kind", c.kind).putOpt("art", c.art)
                    .put("billingSet", c.billingSet)
                    .apply {
                        secrets[c.id]?.let { s ->
                            put("secret", JSONObject().put("number", s.number).put("expiry", s.expiry).put("cvv", s.cvv).put("holder", s.holder))
                        }
                    }
            }))
            .put("payments", JSONArray(d.payments().map {
                JSONObject().put("id", it.id).put("cardId", it.cardId).put("dueEpochDay", it.dueEpochDay)
                    .put("paidOnEpochDay", it.paidOnEpochDay).put("amountPaise", it.amountPaise)
            }))
            .put("statements", JSONArray(d.statements().map { s ->
                JSONObject().put("id", s.id).put("cardId", s.cardId).put("statementEpochDay", s.statementEpochDay)
                    .putOpt("periodStartEpochDay", s.periodStartEpochDay).putOpt("dueEpochDay", s.dueEpochDay)
                    .putOpt("totalDuePaise", s.totalDuePaise).putOpt("minDuePaise", s.minDuePaise).putOpt("openingPaise", s.openingPaise)
                    .put("debitsPaise", s.debitsPaise).put("creditsPaise", s.creditsPaise).put("status", s.status).put("note", s.note)
                    .put("sourceMsgId", s.sourceMsgId).put("fileHash", s.fileHash).put("parser", s.parser).put("rawText", s.rawText)
                    .put("importedAt", s.importedAt).putOpt("rewardPointsBalance", s.rewardPointsBalance)
                    .putOpt("rewardPointsEarned", s.rewardPointsEarned).putOpt("cashbackPaise", s.cashbackPaise)
                    .putOpt("rewardUnit", s.rewardUnit).putOpt("creditLimitPaise", s.creditLimitPaise)
            }))
            .put("transactions", JSONArray(d.txns().map { t ->
                JSONObject().put("id", t.id).put("cardId", t.cardId).putOpt("statementId", t.statementId).put("epochDay", t.epochDay)
                    .put("merchant", t.merchant).put("amountPaise", t.amountPaise).put("isCredit", t.isCredit)
                    .putOpt("categoryId", t.categoryId).put("reference", t.reference).put("dedupeKey", t.dedupeKey)
                    .put("kind", t.kind).put("note", t.note)
            }))
            .put("categories", JSONArray(d.categories().map { JSONObject().put("id", it.id).put("name", it.name).put("color", it.color) }))
            .put("rules", JSONArray(d.rules().map {
                JSONObject().put("id", it.id).put("categoryId", it.categoryId).put("pattern", it.pattern).put("priority", it.priority)
            }))
            .put("importLog", JSONArray(d.importLog().map { l ->
                JSONObject().put("key", l.key).put("msgId", l.msgId).put("subject", l.subject).put("sender", l.sender)
                    .put("receivedAt", l.receivedAt).put("outcome", l.outcome).put("detail", l.detail)
                    .putOpt("statementId", l.statementId).put("at", l.at)
            }))
            .put("senderCards", JSONArray(d.senderCards().map { JSONObject().put("sender", it.sender).put("cardId", it.cardId) }))
            .put("holderNames", JSONArray(db.dao().holderNames().map {
                JSONObject().put("name", it.name).put("lastUsed", it.lastUsed).put("uses", it.uses)
            }))
            .put("prefs", JSONObject()
                .put("lockTimeoutSec", prefs.lockTimeoutSec).put("defaultLeads", prefs.defaultLeads)
                .put("palette", prefs.palette).put("themeMode", prefs.themeMode))
        d.mailSource()?.let { m ->
            o.put("mail", JSONObject().put("host", m.host).put("port", m.port).put("user", m.user)
                .put("extraSenders", m.extraSenders).put("lookbackDays", m.lookbackDays)
                .putOpt("profile", runCatching { MailCrypt.dec(m.profileEnc) }.getOrNull()))
        }
        return o.toString().toByteArray()
    }

    fun parse(plain: ByteArray): Contents {
        val json = runCatching { JSONObject(String(plain)) }.getOrNull() ?: throw WrongPassword()
        if (json.optInt("format") > FORMAT) throw IllegalStateException("This backup is from a newer version of Cards. Update the app first.")
        return Contents(json)
    }

    // ---------- Restore ----------

    /**
     * Replaces everything with the backup. [encrypt] turns plain secrets into this phone's vault blobs
     * (needs an unlocked vault). Returns the number of cards restored.
     */
    suspend fun restore(db: AppDatabase, contents: Contents, prefs: Prefs, encrypt: (SecretDraft) -> CardSecretBlobs): Int {
        val j = contents.json
        val d = db.backup()
        fun arr(k: String) = j.optJSONArray(k) ?: JSONArray()
        fun JSONObject.lOrNull(k: String) = if (has(k) && !isNull(k)) getLong(k) else null
        fun JSONObject.sOrNull(k: String) = if (has(k) && !isNull(k)) getString(k) else null
        fun <T> map(k: String, f: (JSONObject) -> T) = (0 until arr(k).length()).map { f(arr(k).getJSONObject(it)) }

        val cards = map("cards") { c ->
            Card(
                id = c.getLong("id"), nickname = c.getString("nickname"), issuer = c.getString("issuer"), network = c.getString("network"),
                last4 = c.getString("last4"), limitPaise = c.optLong("limitPaise"), outstandingPaise = c.optLong("outstandingPaise"),
                statementDay = c.optInt("statementDay", 1), dueOffsetDays = c.optInt("dueOffsetDays", 20), color = c.getLong("color"),
                pinned = c.optBoolean("pinned"), reminderLeads = c.optString("reminderLeads", "5,2,0"), notes = c.optString("notes"),
                createdAt = c.optLong("createdAt", System.currentTimeMillis()), archivedAt = c.lOrNull("archivedAt"),
                kind = c.optString("kind", CardKind.CREDIT),
                art = c.optString("art").ifBlank { null },
                // Older backups predate optional billing: their cycles were always set.
                billingSet = c.optBoolean("billingSet", true),
            )
        }
        // Encrypt before touching the database, so a failed unlock leaves everything as it was.
        val secrets = map("cards") { c ->
            c.optJSONObject("secret")?.let { s ->
                val draft = SecretDraft(s.optString("number"), s.optString("expiry"), s.optString("cvv"), s.optString("holder"))
                if (draft.isEmpty) null else encrypt(draft).let { b -> CardSecret(c.getLong("id"), b.number, b.expiry, b.cvv, b.holder) }
            }
        }.filterNotNull()

        db.withTransaction {
            d.clearSenderCards(); d.clearPendingPdfs(); d.clearImportLog(); d.clearTxns(); d.clearStatements()
            d.clearPayments(); d.clearSecrets(); d.clearCards(); d.clearRules(); d.clearCategories()

            d.putCards(cards)
            d.putSecrets(secrets)
            d.putPayments(map("payments") { Payment(it.getLong("id"), it.getLong("cardId"), it.getLong("dueEpochDay"), it.getLong("paidOnEpochDay"), it.getLong("amountPaise")) })
            d.putCategories(map("categories") { Category(it.getLong("id"), it.getString("name"), it.getLong("color")) })
            d.putRules(map("rules") { CategoryRule(it.getLong("id"), it.getLong("categoryId"), it.getString("pattern"), it.getInt("priority")) })
            d.putStatements(map("statements") { s ->
                Statement(
                    id = s.getLong("id"), cardId = s.getLong("cardId"), statementEpochDay = s.getLong("statementEpochDay"),
                    periodStartEpochDay = s.lOrNull("periodStartEpochDay"), dueEpochDay = s.lOrNull("dueEpochDay"),
                    totalDuePaise = s.lOrNull("totalDuePaise"), minDuePaise = s.lOrNull("minDuePaise"), openingPaise = s.lOrNull("openingPaise"),
                    debitsPaise = s.optLong("debitsPaise"), creditsPaise = s.optLong("creditsPaise"), status = s.optString("status", Ledger.STATUS_OK),
                    note = s.optString("note"), sourceMsgId = s.optString("sourceMsgId"), fileHash = s.getString("fileHash"),
                    parser = s.optString("parser"), rawText = s.optString("rawText"), importedAt = s.optLong("importedAt"),
                    rewardPointsBalance = s.lOrNull("rewardPointsBalance"), rewardPointsEarned = s.lOrNull("rewardPointsEarned"),
                    cashbackPaise = s.lOrNull("cashbackPaise"), rewardUnit = s.sOrNull("rewardUnit"), creditLimitPaise = s.lOrNull("creditLimitPaise"),
                )
            })
            d.putTxns(map("transactions") { t ->
                Txn(
                    id = t.getLong("id"), cardId = t.getLong("cardId"), statementId = t.lOrNull("statementId"), epochDay = t.getLong("epochDay"),
                    merchant = t.getString("merchant"), amountPaise = t.getLong("amountPaise"), isCredit = t.getBoolean("isCredit"),
                    categoryId = t.lOrNull("categoryId"), reference = t.optString("reference"), dedupeKey = t.getString("dedupeKey"),
                    kind = t.optString("kind", "spend"), note = t.optString("note"),
                )
            })
            // Waiting PDFs aren't in the backup, so "locked" / "unmatched" rows would point at nothing. Drop them:
            // the next sync then sees those emails as new and fetches the PDFs again.
            d.putImportLog(map("importLog") { l ->
                ImportLog(
                    l.getString("key"), l.optString("msgId"), l.optString("subject"), l.optString("sender"), l.optLong("receivedAt"),
                    l.optString("outcome"), l.optString("detail"), l.lOrNull("statementId"), l.optLong("at"),
                )
            }.filter { it.outcome !in PENDING_PDF_OUTCOMES })
            d.putSenderCards(map("senderCards") { SenderCard(it.getString("sender"), it.getLong("cardId")) })

            // Saved names. Older backups have none: take them from the cards' own holder names instead.
            val names = if (j.has("holderNames")) map("holderNames") { n ->
                HolderName(HolderName.normalise(n.getString("name")), n.optLong("lastUsed"), n.optInt("uses"))
            } else map("cards") { c -> c.optJSONObject("secret")?.optString("holder").orEmpty() }
                .map { HolderName.normalise(it) }.filter { it.isNotEmpty() }
                .groupingBy { it }.eachCount().map { (n, uses) -> HolderName(n, System.currentTimeMillis(), uses) }
            db.dao().clearHolderNames()
            db.dao().putHolderNames(names.filter { it.name.isNotEmpty() }.distinctBy { it.name })

            // Email: keep this phone's saved app password if the account is the same; otherwise it must be re-entered.
            val old = d.mailSource()
            d.clearMailSource()
            j.optJSONObject("mail")?.let { m ->
                val user = m.optString("user")
                d.putMailSource(
                    MailSource(
                        host = m.optString("host", "imap.gmail.com"), port = m.optInt("port", 993), user = user,
                        passwordEnc = old?.takeIf { it.user.equals(user, true) }?.passwordEnc,
                        extraSenders = m.optString("extraSenders"),
                        profileEnc = m.sOrNull("profile")?.let { MailCrypt.enc(it) },
                        lookbackDays = m.optInt("lookbackDays", 120),
                    ),
                )
            }
        }

        j.optJSONObject("prefs")?.let { p ->
            prefs.lockTimeoutSec = p.optInt("lockTimeoutSec", prefs.lockTimeoutSec)
            prefs.defaultLeads = p.optString("defaultLeads", prefs.defaultLeads)
            prefs.palette = p.optString("palette", prefs.palette)
            prefs.themeMode = p.optString("themeMode", prefs.themeMode)
        }
        return cards.size
    }

    /** Vault output for one card, without importing security types into the data layer. */
    class CardSecretBlobs(val number: ByteArray?, val expiry: ByteArray?, val cvv: ByteArray?, val holder: ByteArray?)
}
