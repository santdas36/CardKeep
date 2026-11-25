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
import app.cardkeep.domain.Billing
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.ingest.TxnKind
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The card screen's spend log summary: what's been spent this cycle (credit) or month (debit, prepaid), the
 * latest available balance from an alert, and spends that don't agree with a statement. Hidden until the
 * card has spends, or spend tracking is on. [lastStatementDay] is the newest imported statement's date.
 */
@Composable
fun CardSpendLog(card: Card, lastStatementDay: Long?) {
    val context = LocalContext.current
    val app = remember { CardsApp.from(context) }
    val all by app.repo.spendLog.spends.collectAsStateWithLifecycle(initialValue = emptyList())
    val mine = all.filter { it.cardId == card.id }
    if (mine.isEmpty() && !app.prefs.readSms && !app.prefs.readNotifications) return

    val today = LocalDate.now()
    val sinceDay = when {
        !card.isCredit -> today.withDayOfMonth(1).toEpochDay() - 1
        lastStatementDay != null -> lastStatementDay
        else -> {
            val thisMonth = Billing.statementDate(java.time.YearMonth.from(today), card.statementDay)
            (if (thisMonth.isAfter(today)) Billing.statementDate(java.time.YearMonth.from(today).minusMonths(1), card.statementDay) else thisMonth).toEpochDay()
        }
    }
    val period = mine.filter { it.epochDay > sinceDay && it.currency == "INR" && TxnKind.spendSign(it.kind, it.isCredit) != 0 }
    val spent = period.sumOf { it.amountPaise * TxnKind.spendSign(it.kind, it.isCredit) }
    val available = mine.filter { it.availablePaise != null }.maxByOrNull { it.atMillis }
    val flagged = mine.filter { SpendReview.flagged(it.review) }
    var showFlagged by remember { mutableStateOf(false) }
    var showCycle by remember { mutableStateOf(false) }
    // Spend being edited from the cycle list; NEW_SPEND for a new one.
    var editing by remember { mutableStateOf<Spend?>(null) }
    val title = if (card.isCredit) "This cycle" else "This month"

    SectionHeader("Spend log")
    Panel(padded = false) {
        ListRow(
            Icons.AutoMirrored.Outlined.ReceiptLong, title,
            trailing = if (period.isEmpty()) "No spends yet" else "${Money.inr(spent, true)} · ${period.size} spend${if (period.size == 1) "" else "s"}",
            chevron = true, onClick = { showCycle = true },
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

    val e = editing
    if (e != null) {
        val cards by app.repo.everyCard.collectAsStateWithLifecycle(initialValue = emptyList())
        val categories by app.repo.ledger.categories.collectAsStateWithLifecycle(initialValue = emptyList())
        SpendSheet(
            spend = e.takeIf { it !== NEW_SPEND }, cards = cards.map { it.card }, categories = categories,
            onDismiss = { editing = null }, defaultCardId = card.id,
        )
    } else if (showCycle) {
        val categories by app.repo.ledger.categories.collectAsStateWithLifecycle(initialValue = emptyList())
        CycleSheet(
            title = title,
            since = LocalDate.ofEpochDay(sinceDay + 1),
            spends = mine.filter { it.epochDay > sinceDay }.sortedByDescending { it.atMillis },
            spent = spent,
            catById = categories.associateBy { it.id },
            onOpen = { editing = it },
            onAdd = { editing = NEW_SPEND },
            onDismiss = { showCycle = false },
        )
    }
}

/** Marks "add a new spend" in [CardSpendLog]'s editing state. */
private val NEW_SPEND = Spend(
    cardId = 0, atMillis = 0, epochDay = 0, amountPaise = 0, isCredit = false, kind = TxnKind.SPEND, currency = "INR",
    merchant = "", categoryId = null, note = "", source = "", availablePaise = null, reference = "", dedupeKey = "",
    review = SpendReview.NONE, statementId = null, statementTxnId = null,
)

/** Every spend logged for the card since the cycle (or month) began. Tap one to edit it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CycleSheet(
    title: String,
    since: LocalDate,
    spends: List<Spend>,
    spent: Long,
    catById: Map<Long, app.cardkeep.data.Category>,
    onOpen: (Spend) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (LocalLocked.current) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Since ${Dates.short(since)} · ${Money.inr(spent, true)} spent",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onAdd) { Text("Add spend") }
            }
            if (spends.isEmpty()) {
                Text(
                    "Nothing logged yet. Spends show up here as your bank's alerts arrive, or add one yourself.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                Panel(padded = false, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    spends.forEachIndexed { i, s ->
                        if (i > 0) RowDivider(inset = false)
                        SpendRow(s, cw = null, cat = s.categoryId?.let(catById::get), withDate = true, onClick = { onOpen(s) })
                    }
                }
            }
        }
    }
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
                            if (s.currency == "INR") Money.inr(s.amountPaise, true) else "%.2f %s".format(s.amountPaise / 100.0, s.currency),
                            style = MaterialTheme.typography.bodyLarge.tabular, modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        TextButton(onClick = { scope.launch { log.dismiss(s) } }) { Text("Looks fine") }
                    }
                }
            }
        }
    }
}
