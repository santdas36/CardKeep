package app.cardkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Category
import app.cardkeep.data.Spend
import app.cardkeep.domain.Money
import app.cardkeep.ingest.TxnKind
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The month's spends (net of refunds; payments and cashback left out), change vs last month, the top
 * categories as one stacked bar plus chips, and payments/cashback kept apart. Follows the month and
 * filters above, since that's what the list below shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpendSummary(list: List<Spend>, previous: List<Spend>, ym: YearMonth, catById: Map<Long, Category>, onCategory: (Long) -> Unit) {
    val spent = netSpent(list)
    val before = netSpent(previous)
    val payments = list.filter { it.currency == "INR" && it.kind == TxnKind.PAYMENT }.sumOf { it.amountPaise }
    val cashback = list.filter { it.currency == "INR" && it.kind == TxnKind.CASHBACK }.sumOf { if (it.isCredit) it.amountPaise else -it.amountPaise }
    val monthName = ym.month.name.lowercase().replaceFirstChar(Char::uppercase)

    Panel(Modifier.padding(horizontal = Gutter, vertical = 8.dp)) {
        Column {
            Text("$monthName spends", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Money.inr(spent.coerceAtLeast(0), true), style = MaterialTheme.typography.headlineMedium.tabular)
                if (before > 0 && spent > 0) {
                    val pct = ((spent - before) * 100.0 / before).roundToInt()
                    Text(
                        (if (pct >= 0) "  ↑ $pct%" else "  ↓ ${abs(pct)}%") + " vs ${ym.minusMonths(1).month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)}",
                        style = MaterialTheme.typography.bodySmall, color = if (pct > 0) Amber else Good,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            if (spent > 0) {
                Spacer(Modifier.height(12.dp))
                CategoryBreakdown(list, catById, onCategory)
            }
            if (payments > 0 || cashback > 0) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    listOfNotNull(
                        payments.takeIf { it > 0 }?.let { "Paid to cards ${Money.inr(it, true)}" },
                        cashback.takeIf { it > 0 }?.let { "Cashback ${Money.inr(it, true)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The top categories of [list] as one stacked bar plus chips; tapping a chip calls [onCategory]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryBreakdown(list: List<Spend>, catById: Map<Long, Category>, onCategory: (Long) -> Unit) {
    val spent = netSpent(list)
    val byCat = list.filter { TxnKind.spendSign(it.kind, it.isCredit) != 0 }
        .groupBy { it.categoryId }
        .mapValues { (_, l) -> netSpent(l) }
        .filterValues { it > 0 }
        .toList().sortedByDescending { it.second }
    if (byCat.isEmpty() || spent <= 0) return
    val top = byCat.take(4)
    val other = byCat.drop(4).sumOf { it.second }
    fun colorOf(id: Long?) = id?.let { catById[it] }?.let { Color(it.color) }
    // Weights are the amounts themselves (all > 0, as Modifier.weight requires).
    Row(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        top.forEach { (id, v) ->
            Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(colorOf(id) ?: MaterialTheme.colorScheme.outline))
        }
        if (other > 0) Box(Modifier.weight(other.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        top.forEach { (id, v) ->
            CategoryChip(
                id?.let { catById[it] }?.name ?: "Uncategorised", (v * 100 / spent).coerceIn(0L, 100L).toInt(),
                colorOf(id) ?: MaterialTheme.colorScheme.outline,
                onClick = id?.let { { onCategory(it) } },
            )
        }
    }
}

/** Small pill: the category's dot (matching its bar segment), name and share. */
@Composable
private fun CategoryChip(name: String, pct: Int, color: Color, onClick: (() -> Unit)?) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        Text(" $pct%", style = MaterialTheme.typography.labelMedium.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
