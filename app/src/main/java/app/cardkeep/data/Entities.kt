package app.cardkeep.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One credit card. Money is integer paise. Nothing sensitive lives here; see [CardSecret]. */
@Entity(tableName = "cards")
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String,
    val issuer: String,
    val network: String,
    val last4: String,
    val limitPaise: Long = 0,
    val outstandingPaise: Long = 0,
    /** Day of month the statement is generated (1..31, clamped to month length). */
    val statementDay: Int = 1,
    /** Days from statement date to payment due date. */
    val dueOffsetDays: Int = 20,
    val color: Long,
    val pinned: Boolean = false,
    /** Comma-separated days-before-due for reminders, e.g. "5,2,0". */
    val reminderLeads: String = "5,2,0",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Set when the card is archived (closed / deactivated). Archived cards leave the stack, totals and
     * reminders, and new statements aren't matched to them; their history and vault data stay.
     */
    val archivedAt: Long? = null,
    /** "credit", "debit" or "prepaid". Only credit cards have statements, dues and reminders. */
    @androidx.room.ColumnInfo(defaultValue = "credit") val kind: String = CardKind.CREDIT,
    /** Card design: null = auto (matched from bank and nickname), "bank" = bank colours, else an art id. */
    val art: String? = null,
    /**
     * False when a credit card was added without a due date: [statementDay]/[dueOffsetDays] are then only
     * placeholders, so it has no dues or reminders until you set one (or a statement is imported).
     */
    @ColumnInfo(defaultValue = "1") val billingSet: Boolean = true,
)

object CardKind {
    const val CREDIT = "credit"
    const val DEBIT = "debit"
    const val PREPAID = "prepaid"
    val all = listOf(CREDIT, DEBIT, PREPAID)
    fun label(kind: String) = when (kind) { DEBIT -> "Debit"; PREPAID -> "Prepaid"; else -> "Credit" }
}

val Card.isCredit: Boolean get() = kind == CardKind.CREDIT

/** A credit card whose cycle is known: only these have dues, totals and reminders. */
val Card.hasDues: Boolean get() = isCredit && billingSet

/** Per-field AES-GCM blobs (iv || ciphertext), each under its own auth-bound Keystore key. */
@Entity(
    tableName = "card_secrets",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
)
class CardSecret(
    @PrimaryKey val cardId: Long,
    val numberEnc: ByteArray?,
    val expiryEnc: ByteArray?,
    val cvvEnc: ByteArray?,
    val holderEnc: ByteArray?,
)

/** One parsed statement. [fileHash] and [sourceMsgId] make import idempotent. */
@Entity(
    tableName = "statements",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index(value = ["fileHash"], unique = true)],
)
data class Statement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val statementEpochDay: Long,
    val periodStartEpochDay: Long?,
    val dueEpochDay: Long?,
    val totalDuePaise: Long?,
    val minDuePaise: Long?,
    val openingPaise: Long?,
    val debitsPaise: Long,
    val creditsPaise: Long,
    /** "ok" or "needs_review". */
    val status: String,
    /** Human-readable reason when [status] is needs_review. */
    val note: String = "",
    val sourceMsgId: String,
    val fileHash: String,
    val parser: String,
    /** Extracted PDF text, kept (inside the encrypted DB) so statements can be re-parsed offline. */
    val rawText: String,
    val importedAt: Long = System.currentTimeMillis(),
    /** Reward points balance after this statement, if the statement shows one. */
    val rewardPointsBalance: Long? = null,
    /** Points earned in this cycle. */
    val rewardPointsEarned: Long? = null,
    /** Cashback credited or earned in this cycle. */
    val cashbackPaise: Long? = null,
    /** What the issuer calls its points ("points", "monies", ...). */
    val rewardUnit: String? = null,
    /** Total credit limit printed on the statement; the newest one updates the card. */
    val creditLimitPaise: Long? = null,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Statement::class, ["id"], ["statementId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId"), Index("statementId"), Index("epochDay"), Index(value = ["dedupeKey"], unique = true)],
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val statementId: Long?,
    val epochDay: Long,
    val merchant: String,
    val amountPaise: Long,
    val isCredit: Boolean,
    val categoryId: Long?,
    val reference: String = "",
    val dedupeKey: String,
    /** spend, payment, refund, cashback or fee. Only spend and fee count as spending. See [app.cardkeep.ingest.TxnKind]. */
    @ColumnInfo(defaultValue = "spend") val kind: String = "spend",
    /** Your own note on this line. */
    @ColumnInfo(defaultValue = "") val note: String = "",
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,
)

/** Merchant substring -> category. Higher priority wins; user edits get a high priority. */
@Entity(
    tableName = "category_rules",
    foreignKeys = [ForeignKey(Category::class, ["id"], ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("categoryId")],
)
data class CategoryRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val pattern: String,
    val priority: Int,
)

/** Single-row IMAP config. Secrets are encrypted with a non-auth Keystore key so sync can run in the background. */
@Entity(tableName = "mail_source")
class MailSource(
    @PrimaryKey val id: Int = 1,
    val host: String = "imap.gmail.com",
    val port: Int = 993,
    val user: String = "",
    val passwordEnc: ByteArray? = null,
    /** Extra sender substrings, comma-separated, on top of the built-in issuer list. */
    val extraSenders: String = "",
    /** JSON of the PDF-password profile (name, DOB, PAN, mobile, custom templates), encrypted. */
    val profileEnc: ByteArray? = null,
    val lookbackDays: Int = 120,
    val lastSyncAt: Long = 0,
    val lastResult: String = "",
)

/** Every mail attachment we looked at, so failures are visible and nothing is retried forever. */
@Entity(tableName = "import_log")
data class ImportLog(
    @PrimaryKey val key: String,
    val msgId: String,
    val subject: String,
    val sender: String,
    val receivedAt: Long,
    /** imported, needs_review, locked, unmatched, unparsed, duplicate, error */
    val outcome: String,
    val detail: String,
    val statementId: Long? = null,
    val at: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")],
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    /** The due date (epoch day) of the cycle this payment settles. */
    val dueEpochDay: Long,
    val paidOnEpochDay: Long,
    val amountPaise: Long,
)

/**
 * A statement PDF we couldn't finish (locked, or no matching card), kept inside the encrypted DB so it
 * can be fixed on the phone without fetching the email again. Dropped once imported.
 */
@Entity(tableName = "pending_pdfs")
class PendingPdf(
    @PrimaryKey val key: String,
    val fileName: String,
    val bytes: ByteArray,
)
/**
 * A cardholder name you've used, offered as a suggestion when adding cards. Kept in the (SQLCipher-encrypted)
 * database rather than the vault, so suggesting one never needs a fingerprint.
 */
@Entity(tableName = "holder_names")
data class HolderName(
    /** Uppercase, trimmed, single-spaced: see [normalise]. */
    @PrimaryKey val name: String,
    val lastUsed: Long,
    /** Cards saved with this name; 0 for one only added from the picker. */
    val uses: Int,
) {
    companion object {
        fun normalise(name: String) = name.trim().replace(Regex("\\s+"), " ").uppercase()
    }
}

/** "Statements from this sender go to this card", learned when you pick a card for an unmatched email. */
@Entity(
    tableName = "sender_cards",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")],
)
data class SenderCard(
    @PrimaryKey val sender: String,
    val cardId: Long,
)
/**
 * One entry in the spend log: a card transaction read from a bank SMS or message notification, or added
 * by hand. Separate from statements: a statement only checks these (see [Spend.review]), never writes them.
 */
@Entity(
    tableName = "spends",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index("epochDay"), Index(value = ["dedupeKey"], unique = true)],
)
data class Spend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    /** When it happened: the time in the message if it has one, else when the message arrived. */
    val atMillis: Long,
    /** Local date of [atMillis], for grouping and statement matching. */
    val epochDay: Long,
    val amountPaise: Long,
    /** Money back to the card (refund, payment, cashback). */
    val isCredit: Boolean,
    /** [app.cardkeep.ingest.TxnKind]: spend, refund, payment or cashback. */
    val kind: String,
    /** "INR", or the alert's currency for spends abroad ([amountPaise] is then in that currency's minor units). */
    val currency: String,
    val merchant: String,
    val categoryId: Long?,
    val note: String,
    /** sms, notification or manual. */
    val source: String,
    /** Available limit (or balance) the alert reported, if any. */
    val availablePaise: Long?,
    val reference: String,
    val dedupeKey: String,
    /** none, matched, missing, differs or dismissed. See [SpendReview]. */
    val review: String,
    /** The statement and line this was matched with. */
    val statementId: Long?,
    val statementTxnId: Long?,
)

object SpendSource {
    const val SMS = "sms"
    const val NOTIFICATION = "notification"
    const val EMAIL = "email"
    const val MANUAL = "manual"
}

object SpendReview {
    /** Not checked yet (current cycle, or no statement for its period). */
    const val NONE = "none"
    const val MATCHED = "matched"
    /** Its statement arrived and doesn't have it. */
    const val MISSING = "missing"
    /** Its statement has it, but for a different amount. */
    const val DIFFERS = "differs"
    /** Looked at and accepted as is. */
    const val DISMISSED = "dismissed"

    fun flagged(review: String) = review == MISSING || review == DIFFERS
}

/**
 * A bank alert that looked like a card transaction but couldn't be logged (unreadable, or no card in the
 * wallet with those digits). Kept a week so missing formats can be spotted; never backed up or exported.
 */
@Entity(tableName = "alert_misses", indices = [Index("at")])
data class AlertMiss(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val at: Long,
    val reason: String,
    val source: String,
)
