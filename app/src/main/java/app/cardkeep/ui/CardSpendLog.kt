package app.cardkeep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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

    SectionHeader("Spend log")
    Panel(padded = false) {
        ListRow(
            Icons.AutoMirrored.Outlined.ReceiptLong, if (card.isCredit && period.isCycle) "This cycle" else "This month",
            subtitle = "Since ${Dates.short(period.start)}",
            trailing = if (counted.isEmpty()) "No spends yet" else "${Money.inr(spent.coerceAtLeast(0), true)} · ${counted.size}",
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
                "These don't agree with the statement. Accept the ones that look right to you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Panel(padded = false) {
                spends.sortedByDescending { it.atMillis }.forEachIndexed { i, s ->
                    if (i > 0) RowDivider(inset = false)
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                Dates.short(LocalDate.ofEpochDay(s.epochDay)) + " · " +
                                    if (s.review == SpendReview.DIFFERS) "Amount differs" else "Missing from statement",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            spendAmountText(s),
                            style = MaterialTheme.typography.bodyLarge.tabular, modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        TextButton(onClick = { scope.launch { log.dismiss(s) } }) { Text("Looks fine") }
                    }
                }
            }
        }
    }
}
