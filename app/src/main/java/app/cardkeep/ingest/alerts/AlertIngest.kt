package app.cardkeep.ingest.alerts

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.cardkeep.CardsApp
import app.cardkeep.data.Card
import app.cardkeep.data.Spend
import app.cardkeep.data.SpendSource
import app.cardkeep.ingest.TxnKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bank alerts in, spend log entries out. Everything stays on the phone: message text is only parsed here,
 * never stored (except a week's worth of alerts that couldn't be read, see [app.cardkeep.data.AlertMiss]).
 */
object AlertIngest {
    private const val DAY = 86_400_000L

    /** How far back the first inbox scan goes. */
    const val BACKFILL_DAYS = 90

    // One alert at a time: the near-duplicate check (SMS and notification of the same message) needs it.
    private val lock = Mutex()

    private val looksLikeCardTxn = Regex(
        "(?is)(spent|debited|credited|refund|reversed|txn|transaction|payment).{0,80}\\bcard\\b|\\bcard\\b.{0,80}(spent|debited|credited|refund|reversed)",
    )

    fun hasSmsPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationAccess(context: Context) =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun listenerComponent(context: Context) = ComponentName(context, AlertListener::class.java)

    /**
     * Logs one alert. [sender] is the SMS address or the notification's title (null if unknown).
     * Returns the new spend, or null if it isn't a card transaction, is already logged, or has no card.
     */
    suspend fun handle(app: CardsApp, sender: String?, body: String, receivedAt: Long, source: String): Spend? = lock.withLock {
        val log = app.repo.spendLog
        val alert = AlertParser.parse(sender, body)
        if (alert == null) {
            if (AlertParser.issuer(sender, body) != null && looksLikeCardTxn.containsMatchIn(body)) {
                log.miss(sender.orEmpty(), body, "Couldn't read this alert", source)
            }
            return@withLock null
        }
        val card = matchCard(app.repo.allCards().filter { it.archivedAt == null }, alert)
        if (card == null) {
            val digits = alert.last4?.let { " ending $it" }.orEmpty()
            log.miss(sender.orEmpty(), body, "No ${alert.issuer ?: "matching"} card$digits in your wallet", source)
            return@withLock null
        }
        log.record(alert, card, source, receivedAt)?.also {
            if (it.kind == TxnKind.PAYMENT) app.repo.autoMarkPaid(card.id)
        }
    }

    /** The wallet card an alert is about: same bank and last digits; without digits, the bank's only card. */
    fun matchCard(cards: List<Card>, alert: CardAlert): Card? {
        val sameBank = cards.filter { alert.issuer == null || it.issuer.equals(alert.issuer, ignoreCase = true) }
        val digits = alert.last4?.filter(Char::isDigit)
        val hits = if (digits.isNullOrEmpty()) {
            if (alert.issuer == null) emptyList() else sameBank
        } else {
            // Amex shows five digits, some banks two or three: compare what both sides have.
            val n = minOf(digits.length, 4)
            sameBank.filter { it.last4.length >= n && it.last4.takeLast(n) == digits.takeLast(n) }
        }
        if (hits.size <= 1) return hits.firstOrNull()
        // Same digits on a credit and a debit card of one bank: the alert usually says which.
        val kind = alert.cardType ?: return if (digits.isNullOrEmpty()) null else hits.first()
        return hits.firstOrNull { it.kind == kind } ?: hits.first()
    }

    /**
     * Reads the SMS inbox from [sinceMillis] (or from where the last scan stopped) and logs card alerts.
     * Returns how many spends were added.
     */
    suspend fun scanInbox(app: CardsApp, sinceMillis: Long? = null): Int {
        if (!app.prefs.readSms || !hasSmsPermission(app)) return 0
        val now = System.currentTimeMillis()
        val since = sinceMillis ?: app.prefs.smsScannedUpTo.takeIf { it > 0 } ?: (now - BACKFILL_DAYS * DAY)
        var added = 0
        var newest = app.prefs.smsScannedUpTo
        val rows = ArrayList<Triple<String, String, Long>>()
        app.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val from = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                // Bank alerts come from named senders; skip people.
                if (from.none(Char::isLetter)) continue
                rows += Triple(from, body, c.getLong(2))
            }
        }
        for ((from, body, date) in rows) {
            if (handle(app, from, body, date, SpendSource.SMS) != null) added++
            if (date > newest) newest = date
        }
        app.prefs.smsScannedUpTo = maxOf(newest, app.prefs.smsScannedUpTo)
        return added
    }
}
