package app.cardkeep.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cardkeep.CardsApp
import app.cardkeep.data.Prefs
import app.cardkeep.ingest.alerts.AlertIngest
import app.cardkeep.ingest.alerts.EmailAlerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SmsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

/**
 * Settings → Spend tracking, in three parts: SMS and message notifications (every bank), alert emails (only
 * the banks you pick, since few send them), and a scan of past days across both, with the alerts that
 * couldn't be read. Everything is read on this phone only.
 */
@Composable
fun SpendTrackingSection(prefs: Prefs, onAlerts: () -> Unit, onMail: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { CardsApp.from(context) }
    val hasTelephony = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }
    val misses by app.repo.spendLog.misses.collectAsStateWithLifecycle(initialValue = emptyList())

    var smsOn by remember { mutableStateOf(prefs.readSms && AlertIngest.hasSmsPermission(context)) }
    var notifPref by remember { mutableStateOf(prefs.readNotifications) }
    var notifAccess by remember { mutableStateOf(AlertIngest.hasNotificationAccess(context)) }
    var awaitingAccess by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<String?>(null) }
    var emailBanks by remember { mutableStateOf(prefs.emailAlertBanks) }
    val mail by app.repo.ledger.mailSource.collectAsStateWithLifecycle(initialValue = null)
    val emailOn = mail?.passwordEnc != null && emailBanks.any { it in EmailAlerts.senders }
    // What Android is blocking ("SMS access" or "notification access"), to point the user at "Allow restricted settings".
    var blocked by remember { mutableStateOf<String?>(null) }

    /** Looks back [days] in SMS and in alert emails, whichever are on. */
    fun scan(days: Int, sms: Boolean = prefs.readSms && AlertIngest.hasSmsPermission(context), email: Boolean = emailOn) {
        if (scanning || (!sms && !email)) return
        scanning = true
        app.scope.launch {
            val since = System.currentTimeMillis() - days * 86_400_000L
            var added = if (sms) AlertIngest.scanInbox(app, since) else 0
            val emailFailed = email && runCatching { added += EmailAlerts.check(context, since) }.isFailure
            withContext(Dispatchers.Main) {
                val text = when (added) { 0 -> "No new spends found"; 1 -> "Added 1 spend"; else -> "Added $added spends" } +
                    if (emailFailed) ". Couldn't read email" else ""
                scanning = false
                scanResult = text
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            prefs.readSms = true
            smsOn = true
            scan(AlertIngest.BACKFILL_DAYS, sms = true, email = false)
        } else if (SmsPermissions.any { !ActivityCompat.shouldShowRequestPermissionRationale(context as Activity, it) }) {
            // Denied without a prompt to answer: Android won't ask again (restricted setting, or "don't ask again").
            blocked = "SMS access"
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notifAccess = AlertIngest.hasNotificationAccess(context)
        smsOn = prefs.readSms && AlertIngest.hasSmsPermission(context)
        if (awaitingAccess) {
            awaitingAccess = false
            if (!notifAccess) blocked = "notification access"
        }
    }

    val notifOn = notifPref && notifAccess

    fun toggleSms() {
        if (smsOn) { prefs.readSms = false; smsOn = false } else askSms.launch(SmsPermissions)
    }

    fun toggleNotifications() {
        if (notifOn) { prefs.readNotifications = false; notifPref = false; return }
        prefs.readNotifications = true
        notifPref = true
        if (notifAccess) return
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, AlertIngest.listenerComponent(context).flattenToString())
        } else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        awaitingAccess = true
        openSystemSettings(context, intent)
    }

    // Every bank: alerts by SMS and in message notifications.
    Panel(padded = false) {
        if (hasTelephony) {
            ListRow(
                Icons.Outlined.Sms, "Bank SMS", "Card alerts from every bank",
                onClick = ::toggleSms,
                action = { Switch(smsOn, { toggleSms() }) },
            )
            RowDivider()
        }
        ListRow(
            Icons.Outlined.Notifications, "Message notifications", "Alerts sent over RCS",
            onClick = ::toggleNotifications,
            action = { Switch(notifOn, { toggleNotifications() }) },
        )
    }

    // Only the banks you pick: few send alerts by email, and some (slice) send nothing else.
    Panel(Modifier.padding(top = 10.dp), padded = false) {
        EmailAlertsRow(emailBanks, onChange = { emailBanks = it; prefs.emailAlertBanks = it }, onMail = onMail)
    }

    // Look back across whichever of the two is on.
    val canScan = smsOn || emailOn
    val sources = listOfNotNull("SMS".takeIf { smsOn }, "email".takeIf { emailOn }).joinToString(" and ")
    Panel(Modifier.padding(top = 10.dp), padded = false) {
        Column(Modifier.alpha(if (canScan) 1f else 0.5f)) {
            ListRow(
                Icons.Outlined.History, "Scan past ${AlertIngest.BACKFILL_DAYS} days",
                when {
                    !canScan -> "Turn on Bank SMS or email alerts first"
                    scanning -> "Reading your $sources…"
                    else -> scanResult ?: "Find spends you missed in $sources"
                },
                onClick = if (canScan && !scanning) ({ scan(AlertIngest.BACKFILL_DAYS) }) else null,
                action = when {
                    scanning -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
                    // Bank alerts that couldn't be logged: a small warning that opens the list.
                    misses.isNotEmpty() -> ({ StatusPill("${misses.size} unread", Amber, onClick = onAlerts) })
                    else -> null
                },
            )
        }
    }

    blocked?.let { what ->
        ConfirmDialog(
            title = "Android is blocking this",
            body = "Not allowed for apps from outside the Play Store. Open App info → ⋮ → Allow restricted settings, then try again.",
            confirmLabel = "Open App info",
            destructive = false,
            onDismiss = { blocked = null },
            onConfirm = {
                blocked = null
                openSystemSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            },
        )
    }
}
