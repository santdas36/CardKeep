package app.cardkeep.ingest.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.cardkeep.CardsApp
import app.cardkeep.data.MailCrypt
import app.cardkeep.ingest.MailClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Card alerts sent by email, for banks that email them (some, like slice, send no SMS). Read over the same
 * IMAP sign-in as statements, from the banks you turn on, about every 15 minutes.
 */
object EmailAlerts {
    /** Banks whose alert emails we can read, and the addresses they come from. */
    val senders: Map<String, List<String>> = linkedMapOf(
        "HDFC Bank" to listOf("alerts@hdfcbank.bank.in", "alerts@hdfcbank.net"),
        "Slice" to listOf("noreply@slice.bank.in"),
    )

    /** How far back the first check looks. */
    private const val FIRST_LOOK_DAYS = 3L
    private const val DAY = 86_400_000L
    private val lock = Mutex()

    /**
     * Reads new alert emails from the turned-on banks and logs them. With [sinceMillis] (a scan of past days)
     * it reads everything from then, not just what's new. Returns how many spends were added.
     */
    suspend fun check(context: Context, sinceMillis: Long? = null): Int = withContext(Dispatchers.IO) {
        val app = CardsApp.from(context)
        lock.withLock {
            val banks = app.prefs.emailAlertBanks.filter { it in senders }
            if (banks.isEmpty()) return@withLock 0
            val src = app.repo.ledger.mailSourceOnce() ?: return@withLock 0
            val password = MailCrypt.dec(src.passwordEnc) ?: return@withLock 0
            val now = System.currentTimeMillis()
            val since = sinceMillis ?: app.prefs.emailAlertsUpTo.takeIf { it > 0 } ?: (now - FIRST_LOOK_DAYS * DAY)
            val mails = MailClient(src.host, src.port, src.user, password)
                .fetchAlerts(Date(since), banks.flatMap { senders.getValue(it) }, limit = if (sinceMillis != null) 500 else 100)
            var added = 0
            for (m in mails) {
                if (AlertIngest.handleEmail(app, m.from, m.subject, m.text, m.receivedAt) != null) added++
            }
            // A scan of past days never moves the mark back.
            app.prefs.emailAlertsUpTo = maxOf(app.prefs.emailAlertsUpTo, since, mails.maxOfOrNull { it.receivedAt } ?: since)
            added
        }
    }

    /** Turns the 15-minute check on or off to match the settings. */
    fun schedule(context: Context) {
        val app = CardsApp.from(context)
        val wm = WorkManager.getInstance(context)
        if (app.prefs.emailAlertBanks.none { it in senders }) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<EmailAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** An email's date with no time of day ("25-Nov-26") is that day at midnight; the received time is closer. */
    internal fun betterTime(alertAt: Long?, receivedAt: Long): Long? {
        alertAt ?: return null
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        val a = Instant.ofEpochMilli(alertAt).atZone(ist)
        val r = Instant.ofEpochMilli(receivedAt).atZone(ist)
        return if (a.toLocalTime() == java.time.LocalTime.MIDNIGHT && a.toLocalDate() == r.toLocalDate()) receivedAt else alertAt
    }

    private const val WORK = "email_alerts"
}

class EmailAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runCatching { EmailAlerts.check(applicationContext) }.fold({ Result.success() }, { if (runAttemptCount < 2) Result.retry() else Result.success() })
}
