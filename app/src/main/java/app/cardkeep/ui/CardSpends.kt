package app.cardkeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cardkeep.CardsApp
import app.cardkeep.data.Card
import app.cardkeep.data.Spend
import app.cardkeep.data.hasDues
import app.cardkeep.data.isCredit
import app.cardkeep.domain.Billing
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.ingest.TxnKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * A card's unbilled period: what's been spent since the last statement closed, which no statement has yet.
 * Credit cards with a known cycle run from the day after the last closing date to the next one; other cards
 * (debit, prepaid, or no cycle set) use the calendar month.
 */
data class UnbilledPeriod(val start: LocalDate, val end: LocalDate?, val isCycle: Boolean) {
    /** Spends dated after this day belong to the period. */
    val afterDay: Long get() = start.toEpochDay() - 1

    companion object {
        fun of(card: Card, lastStatementDay: Long?, today: LocalDate = LocalDate.now()): UnbilledPeriod {
            if (!card.hasDues) {
                // No cycle known: after the newest statement if there is one, else this month.
                val monthStart = today.withDayOfMonth(1)
                val start = lastStatementDay?.let { LocalDate.ofEpochDay(it + 1) }?.takeIf { card.isCredit && it.isAfter(monthStart) } ?: monthStart
                return UnbilledPeriod(start, null, false)
            }
            val ym = YearMonth.from(today)
            val thisMonth = Billing.statementDate(ym, card.statementDay)
            // The cycle closes on its statement day, so a statement dated today has already closed it.
            val lastClose = if (thisMonth.isAfter(today)) Billing.statementDate(ym.minusMonths(1), card.statementDay) else thisMonth
            // An imported statement newer than the cycle maths (a moved statement date) wins.
            val closed = maxOf(lastClose.toEpochDay(), lastStatementDay ?: Long.MIN_VALUE)
            val start = LocalDate.ofEpochDay(closed + 1)
            val next = Billing.statementDate(YearMonth.from(LocalDate.ofEpochDay(closed)).plusMonths(1), card.statementDay)
            return UnbilledPeriod(start, next, true)
        }
    }
}

/** Net spend of [list] in rupees: purchases and fees, less refunds (payments and cashback aren't spending). */
fun netSpent(list: List<Spend>) = list.filter { it.currency == "INR" }.sumOf { TxnKind.spendSign(it.kind, it.isCredit) * it.amountPaise }

/**
 * The card's spend tracker: everything logged since its last statement closed, by day, with what that adds
 * up to, how far into the cycle you are, and where it went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardSpendsScreen(cardId: Long, onBack: () -> Unit) {
    val app = CardsApp.from(LocalContext.current)
    val cards by app.repo.everyCard.collectAsStateWithLifecycle(initialValue = emptyList())
    val spends by app.repo.spendLog.spends.collectAsStateWithLifecycle(initialValue = emptyList())
    val statements by app.repo.ledger.statements.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repo.ledger.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val cw = cards.firstOrNull { it.card.id == cardId }
    var open by remember { mutableStateOf<Spend?>(null) }
    var adding by remember { mutableStateOf(false) }
    var catFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    val catById = remember(categories) { categories.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (cw?.card?.hasDues != false) "This cycle" else "This month")
                        cw?.let { Text("${it.card.nickname} •• ${it.card.last4}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { adding = true }, enabled = cw != null) { Icon(Icons.Filled.Add, "Add a spend") } },
            )
        },
    ) { pad ->
        val card = cw?.card ?: return@Scaffold
        val period = UnbilledPeriod.of(card, statements.filter { it.cardId == card.id }.maxOfOrNull { it.statementEpochDay })
        val mine = spends.filter { it.cardId == card.id && it.epochDay > period.afterDay }.sortedByDescending { it.atMillis }
        val shown = mine.filter { catFilter == null || it.categoryId == catFilter }
        val available = spends.filter { it.cardId == card.id && it.availablePaise != null }.maxByOrNull { it.atMillis }

        LazyColumn(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            item { PeriodHeader(period, mine, available, catById) { catFilter = it } }
            catFilter?.let { id ->
                item {
                    InputChip(
                        selected = true, onClick = { catFilter = null }, label = { Text(catById[id]?.name ?: "Category") },
                        trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.padding(0.dp)) },
                        modifier = Modifier.padding(horizontal = Gutter),
                    )
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (mine.isEmpty()) "Nothing logged since ${Dates.short(period.start)}. Spends show up here as your bank's alerts arrive, or add one with +."
                        else "Nothing in this category yet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            shown.groupBy { it.epochDay }.forEach { (day, list) ->
                item(key = "d$day") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
                        Text(Dates.weekday(LocalDate.ofEpochDay(day)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        val daySpent = netSpent(list)
                        if (daySpent > 0) Text(Money.inr(daySpent, true), style = MaterialTheme.typography.labelLarge.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.id }) { s -> SpendRow(s, cw = null, cat = s.categoryId?.let(catById::get)) { open = s } }
            }
        }
    }

    if (!LocalLocked.current && (adding || open != null)) {
        SpendSheet(
            spend = open, cards = cards.map { it.card }, categories = categories,
            onDismiss = { open = null; adding = false }, defaultCardId = cardId,
        )
    }
}

/** Spent so far, the period and how far into it we are, the latest available limit, and the category split. */
@Composable
private fun PeriodHeader(
    period: UnbilledPeriod,
    list: List<Spend>,
    available: Spend?,
    catById: Map<Long, app.cardkeep.data.Category>,
    onCategory: (Long) -> Unit,
) {
    val today = LocalDate.now()
    val spent = netSpent(list)
    val count = list.count { TxnKind.spendSign(it.kind, it.isCredit) > 0 }
    val credits = list.filter { it.isCredit && it.currency == "INR" }.sumOf { it.amountPaise }

    Panel(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
        Column {
            Text("Spent so far", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.inr(spent.coerceAtLeast(0), true), style = MaterialTheme.typography.headlineMedium.tabular)
            val end = period.end
            if (period.isCycle && end != null) {
                val total = ChronoUnit.DAYS.between(period.start, end).coerceAtLeast(1)
                val gone = ChronoUnit.DAYS.between(period.start, today).coerceIn(0, total)
                val left = ChronoUnit.DAYS.between(today, end)
                Text(
                    "${Dates.short(period.start)} – ${Dates.short(end)} · " + when {
                        left <= 0 -> "closes today"
                        left == 1L -> "closes tomorrow"
                        else -> "closes in $left days"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { gone.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {},
                )
            } else {
                Text("Since ${Dates.short(period.start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                StatTile("Spends", "$count", Modifier.weight(1f))
                StatTile("Back to card", if (credits > 0) Money.inr(credits, true) else "–", Modifier.weight(1f))
                StatTile(
                    "Available", available?.availablePaise?.let { Money.inr(it, true) } ?: "–", Modifier.weight(1f),
                    sub = available?.let { relativeTime(it.atMillis) },
                )
            }
            if (spent > 0) {
                Spacer(Modifier.height(14.dp))
                CategoryBreakdown(list, catById, onCategory)
            }
        }
    }
}

/** A small figure with its label, inside the header panel. */
@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, sub: String? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall.tabular, maxLines = 1)
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}
