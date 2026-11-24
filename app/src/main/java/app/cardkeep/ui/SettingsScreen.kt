package app.cardkeep.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.cardkeep.data.Prefs
import app.cardkeep.domain.Billing
import app.cardkeep.reminders.Notifications
import app.cardkeep.reminders.Reminders

/**
 * Appearance first, then what you set once (reminders, security), then where data comes from and goes
 * (statements, backup, archive), then About.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(prefs: Prefs, onMail: () -> Unit, onPasswords: () -> Unit = {}, onBack: () -> Unit, onArchived: () -> Unit = {}, archivedCount: Int = 0) {
    val context = LocalContext.current
    var timeout by remember { mutableIntStateOf(prefs.lockTimeoutSec) }
    var leads by remember { mutableStateOf(prefs.defaultLeads) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 32.dp)) {
            SectionHeader("Appearance")
            Panel { AppearanceSection(prefs) }

            SectionHeader("Reminders")
            Panel {
                val chosen = Billing.parseLeads(leads)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 5, 3, 2, 1, 0).forEach { day ->
                        FilterChip(
                            selected = day in chosen,
                            onClick = {
                                val next = if (day in chosen) chosen - day else chosen + day
                                leads = next.sortedDescending().joinToString(","); prefs.defaultLeads = leads
                            },
                            label = { Text(reminderLeadLabel(day)) },
                        )
                    }
                }
                Text(
                    "For new cards. Reminders arrive at 9 am; each card can change these.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            ReminderHealth(tick)

            SectionHeader("Security")
            Panel(padded = false) {
                LockTimeoutRow(timeout) { timeout = it; prefs.lockTimeoutSec = it }
                RowDivider()
                AutofillRow()
            }

            SectionHeader("Statements")
            Panel(padded = false) {
                // Statement passwords live inside Email statements, next to the imports that use them.
                ListRow(Icons.Outlined.Mail, "Email statements", "Gmail import and statement passwords", chevron = true, onClick = onMail)
            }

            SectionHeader("Data")
            BackupSection()
            Spacer(Modifier.height(8.dp))
            Panel(padded = false) {
                ListRow(
                    Icons.Outlined.Archive, "Archived cards", if (archivedCount == 0) "None" else "$archivedCount archived",
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant, chevron = true, onClick = onArchived,
                )
            }

            SectionHeader("About")
            Panel(padded = false) {
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                ListRow(Icons.Outlined.Info, "Cards", "Version $version")
                RowDivider()
                ListRow(
                    Icons.Outlined.Code, "Source code", "github.com/santdas36/CardKeep", chevron = true,
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/santdas36/CardKeep"))) } },
                )
                RowDivider()
                ListRow(Icons.Outlined.Gavel, "Licence", "GPL-3.0")
                RowDivider()
                ListRow(Icons.Outlined.Storage, "Card BIN data", "binlist-data by Ian Nuttall and bin-list-data by Venelin Kochev, CC BY 4.0")
            }
            Text(
                "Everything stays on this phone. The only network use is reading statement emails from your own mailbox.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Opens a system settings screen; some phones don't have every one, so fall back to this app's
 * details page, then to the main Settings screen.
 */
internal fun openSystemSettings(context: android.content.Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}

/**
 * Asks Android directly for this app ("Let Cards always run in the background?"). Where a phone doesn't
 * have that prompt, opens the app's own settings page instead of the full battery list.
 */
@android.annotation.SuppressLint("BatteryLife")
internal fun requestBatteryExemption(context: android.content.Context) {
    val pkg = Uri.parse("package:${context.packageName}")
    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }
        .onFailure { openSystemSettings(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) }
}

/** One green line while everything is fine; only the failing checks (tap to fix) when something isn't. */
@Composable
private fun ReminderHealth(tick: Int) {
    val context = LocalContext.current
    val notif = remember(tick) { Notifications.enabled(context) }
    val exact = remember(tick) { Reminders.canExact(context) }
    val battery = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    Panel(padded = false) {
        if (notif && exact && battery) {
            ListRow(Icons.Filled.CheckCircle, "Reminders are all set", "Notifications, exact alarms and battery are fine", iconTint = Good)
            return@Panel
        }
        if (!notif) {
            Health("Notifications blocked", "Tap to allow, or reminders stay silent.") {
                openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        if (!exact) {
            if (!notif) RowDivider()
            Health("Exact alarms not allowed", "Reminders may arrive late. Tap to allow.") {
                if (Build.VERSION.SDK_INT >= 31) {
                    openSystemSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }
        }
        if (!battery) {
            if (!notif || !exact) RowDivider()
            Health("Battery optimised", "Some phones drop alarms. Tap to let Cards run in the background.") {
                requestBatteryExemption(context)
            }
        }
    }
}

/** A failing system check: amber warning, tap to fix. */
@Composable
private fun Health(title: String, detail: String, onFix: () -> Unit) {
    ListRow(Icons.Filled.Warning, title, detail, iconTint = Amber, chevron = true, onClick = onFix)
}

private val LockChoices = listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes")

/** One row; the choices drop down from it. */
@Composable
private fun LockTimeoutRow(timeout: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(
            Icons.Outlined.Lock, "Lock after leaving the app",
            LockChoices.firstOrNull { it.first == timeout }?.second ?: "After $timeout seconds",
            chevron = true, onClick = { open = true },
        )
        DropdownMenu(open, onDismissRequest = { open = false }, offset = DpOffset(56.dp, 0.dp)) {
            LockChoices.forEach { (sec, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onChange(sec); open = false },
                    trailingIcon = { if (sec == timeout) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

/** Turns Cards on as the phone's autofill service for checkout forms. */
@Composable
private fun AutofillRow() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(android.view.autofill.AutofillManager::class.java) }
    var on by remember { mutableStateOf(manager?.hasEnabledAutofillServices() == true) }
    // Re-check when coming back from the system screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { on = manager?.hasEnabledAutofillServices() == true }
    ListRow(
        Icons.Outlined.CreditCard,
        if (on) "Card autofill is on" else "Fill cards at checkout",
        if (on) "Suggestions show the card name and last four. Filling asks for your fingerprint or PIN."
        else "Use Cards as the phone's autofill service. Every fill asks for your fingerprint or PIN.",
        chevron = true,
        onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        },
    )
}
