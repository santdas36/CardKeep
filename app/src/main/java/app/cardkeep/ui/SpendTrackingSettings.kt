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
import androidx.compose.material.icons.outlined.ErrorOutline
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SmsPermissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

/**
 * Settings → Spend tracking: read bank SMS and message notifications (RCS), scan the inbox again, and the
 * alerts that couldn't be read. Everything is read on this phone only.
 */
@Composable
fun SpendTrackingSection(prefs: Prefs, onAlerts: () -> Unit) {
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
    // What Android is blocking ("SMS access" or "notification access"), to point the user at "Allow restricted settings".
    var blocked by remember { mutableStateOf<String?>(null) }

    fun scan(days: Int) {
        if (scanning) return
        scanning = true
        app.scope.launch {
            val added = AlertIngest.scanInbox(app, System.currentTimeMillis() - days * 86_400_000L)
            withContext(Dispatchers.Main) {
                val text = when (added) { 0 -> "No new spends found"; 1 -> "Added 1 spend"; else -> "Added $added spends" }
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
            scan(AlertIngest.BACKFILL_DAYS)
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

    Text(
        "Log card spends as they happen, from your bank's alerts. Read on your phone only.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
    )
    Panel(padded = false) {
        if (hasTelephony) {
            ListRow(
                Icons.Outlined.Sms, "Bank SMS", "Card alerts in your text messages",
                onClick = ::toggleSms,
                action = { Switch(smsOn, { toggleSms() }) },
            )
            RowDivider()
        }
        ListRow(
            Icons.Outlined.Notifications, "Message notifications (RCS)",
            "Catches alerts sent over RCS. Google Messages, Samsung Messages and others.",
            onClick = ::toggleNotifications,
            action = { Switch(notifOn, { toggleNotifications() }) },
        )
        if (hasTelephony) {
            RowDivider()
            Column(Modifier.alpha(if (smsOn) 1f else 0.5f)) {
                ListRow(
                    Icons.Outlined.History, "Scan past ${AlertIngest.BACKFILL_DAYS} days",
                    when {
                        !smsOn -> "Turn on Bank SMS first"
                        scanning -> "Reading your messages…"
                        else -> scanResult ?: "Look through your inbox for card alerts you missed"
                    },
                    onClick = if (smsOn && !scanning) ({ scan(AlertIngest.BACKFILL_DAYS) }) else null,
                    action = if (scanning) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                )
            }
        }
        RowDivider()
        ListRow(
            Icons.Outlined.ErrorOutline, "Alerts we couldn't read",
            if (misses.isEmpty()) "None" else "${misses.size} in the last 7 days",
            chevron = true, onClick = onAlerts,
        )
    }

    blocked?.let { what ->
        ConfirmDialog(
            title = "Android is blocking this",
            body = "Android may block $what for apps installed outside the Play Store.\n\n" +
                "Open App info → ⋮ (top right) → Allow restricted settings, then turn this on again.",
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
