package app.cardkeep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cardkeep.CardsApp
import app.cardkeep.data.Prefs
import app.cardkeep.ingest.alerts.EmailAlerts
import kotlinx.coroutines.launch

/**
 * The "Email alerts" row under Spend tracking: which banks' alert emails are read. Opens a sheet with a
 * switch per bank, for banks of your cards whose emails we can read. Needs email set up first.
 */
@Composable
fun EmailAlertsRow(prefs: Prefs, onMail: () -> Unit) {
    val context = LocalContext.current
    val app = remember { CardsApp.from(context) }
    val mail by app.repo.ledger.mailSource.collectAsStateWithLifecycle(initialValue = null)
    val cards by app.repo.everyCard.collectAsStateWithLifecycle(initialValue = emptyList())
    val banks = EmailAlerts.senders.keys.filter { bank -> cards.any { it.card.archivedAt == null && it.card.issuer == bank } }
    var on by remember { mutableStateOf(prefs.emailAlertBanks) }
    var sheet by remember { mutableStateOf(false) }
    val connected = mail?.passwordEnc != null
    val active = banks.filter { it in on }

    ListRow(
        Icons.Outlined.Email, "Email alerts",
        when {
            !connected -> "Connect email first"
            banks.isEmpty() -> "None of your banks yet"
            active.isEmpty() -> "For banks that email alerts"
            else -> active.joinToString(", ") + " · every 15 min"
        },
        chevron = true,
        onClick = when {
            !connected -> onMail
            banks.isEmpty() -> null
            else -> ({ sheet = true })
        },
    )

    if (sheet) {
        EmailAlertsSheet(banks, on, onDismiss = { sheet = false }) { bank, enabled ->
            on = if (enabled) on + bank else on - bank
            prefs.emailAlertBanks = on
            EmailAlerts.schedule(context)
            if (enabled) app.scope.launch { runCatching { EmailAlerts.check(context) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmailAlertsSheet(banks: List<String>, on: Set<String>, onDismiss: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Email alerts", style = MaterialTheme.typography.titleLarge)
            Text(
                "For banks that email card alerts. Checked about every 15 minutes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Panel(padded = false) {
                banks.forEachIndexed { i, bank ->
                    if (i > 0) RowDivider()
                    val enabled = bank in on
                    ListRow(
                        icon = null,
                        leading = { BankBadge(bank, size = 34.dp) },
                        title = bank,
                        subtitle = EmailAlerts.senders[bank]?.firstOrNull(),
                        onClick = { onToggle(bank, !enabled) },
                        action = { Switch(enabled, { onToggle(bank, it) }) },
                    )
                }
            }
        }
    }
}
