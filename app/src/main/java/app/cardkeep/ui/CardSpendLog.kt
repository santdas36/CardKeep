package app.cardkeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cardkeep.CardsApp
import app.cardkeep.data.Card
import app.cardkeep.data.Spend
import app.cardkeep.data.SpendReview
import app.cardkeep.data.isCredit
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.ingest.TxnKind
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The card screen's spend log summary: what's been spent since the last statement closed (credit) or this
 * month (debit, prepaid), the latest available balance from an alert, and spends that don't agree with a
 * statement. Hidden until the card has spends, or spend tracking is on. [lastStatementDay] is the newest
 * imported statement's date.
 */
@Composable
fun CardSpendLog(card: Card, lastStatementDay: Long?, onOpenSpends: () -> Unit) {
    val context = LocalContext.current
    val app = remember { CardsApp.from(context) }
    val all by app.repo.spendLog.spends.collectAsStateWithLifecycle(initialValue = emptyList())
    val mine = all.filter { it.cardId == card.id }
    if (mine.isEmpty() && !app.prefs.readSms && !app.prefs.readNotifications) return

    val period = UnbilledPeriod.of(card, lastStatementDay)
    val unbilled = mine.filter { it.epochDay > period.afterDay }
    val counted = unbilled.filter { TxnKind.spendSign(it.kind, it.isCredit) > 0 }
    val spent = netSpent(unbilled)
    val available = mine.filter { it.availablePaise != null }.maxByOrNull { it.atMillis }
    val flagged = mine.filter { SpendReview.flagged(it.review) }
    var showFlagged by remember { mutableStateOf(false) }

    // Only a credit card with a known cycle has a bill; other cards just count the month.
    val billed = card.isCredit && period.isCycle
    SectionHeader(if (billed) "This cycle" else "This month")
    Panel(padded = false) {
        ListRow(
            Icons.AutoMirrored.Outlined.ReceiptLong, if (billed) "Unbilled spends" else "Spends so far",
            subtitle = "Since ${Dates.short(period.start)}" +
                if (counted.isEmpty()) "" else " · ${counted.size} spend${if (counted.size == 1) "" else "s"}",
            trailing = if (counted.isEmpty()) "No spends yet" else Money.inr(spent.coerceAtLeast(0), true),
            chevron = true, onClick = onOpenSpends,
        )
        if (available != null) {
            RowDivider()
            ListRow(
                Icons.Outlined.AccountBalanceWallet, "Available", "As of ${relativeTime(available.atMillis)}",
                trailing = Money.inr(available.availablePaise!!, true),
            )
        }
        if (flagged.isNotEmpty()) {
            RowDivider()
            ListRow(
                Icons.Filled.Warning, "${flagged.size} spend${if (flagged.size == 1) "" else "s"} need${if (flagged.size == 1) "s" else ""} a look",
                iconTint = Amber, chevron = true, onClick = { showFlagged = true },
            )
        }
    }

    if (showFlagged && flagged.isNotEmpty()) FlaggedSheet(flagged, onDismiss = { showFlagged = false })
}

/** Spends that don't agree with a statement, each with a way to accept it as is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlaggedSheet(spends: List<Spend>, onDismiss: () -> Unit) {
    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    val log = CardsApp.from(LocalContext.current).repo.spendLog
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Needs a look", style = MaterialTheme.typography.titleLarge)
            Text(
                "Keep the real ones, remove the rest.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Panel(padded = false) {
                spends.sortedByDescending { it.atMillis }.forEachIndexed { i, s ->
                    if (i > 0) RowDivider(inset = false)
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(s.merchant.ifBlank { TxnKind.label(s.kind) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Dates.short(LocalDate.ofEpochDay(s.epochDay)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(spendAmountText(s), style = MaterialTheme.typography.bodyLarge.tabular, modifier = Modifier.padding(start = 12.dp))
                        }
                        Text(
                            if (s.review == SpendReview.DIFFERS) "The statement shows a different amount." else "The statement doesn't have this spend.",
                            style = MaterialTheme.typography.bodySmall, color = Amber, modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scope.launch { log.delete(s) } }) { Text("Remove", color = Danger) }
                            TextButton(onClick = { scope.launch { log.dismiss(s) } }) { Text("Keep") }
                        }
                    }
                }
            }
        }
    }
}
