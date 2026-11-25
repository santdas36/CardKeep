package app.cardkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Category
import app.cardkeep.data.Spend
import app.cardkeep.data.SpendReview
import app.cardkeep.data.Statement
import app.cardkeep.data.Txn
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Money
import app.cardkeep.ingest.TxnKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** [Spend.source] of a statement line shown in a spend list (see [asEntry]). */
internal const val STATEMENT_SOURCE = "statement"

/** A statement line in the shape of a spend, so one list (and its totals) can show both. Its id is negative. */
internal fun Txn.asEntry() = Spend(
    id = -id, cardId = cardId, atMillis = LocalDate.ofEpochDay(epochDay).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    epochDay = epochDay, amountPaise = amountPaise, isCredit = isCredit, kind = kind, currency = "INR", merchant = merchant,
    categoryId = categoryId, note = note, source = STATEMENT_SOURCE, availablePaise = null, reference = reference, dedupeKey = dedupeKey,
    review = SpendReview.NONE, statementId = statementId, statementTxnId = id,
)

/**
 * What Activity lists: statement lines for billed cycles, and logged spends only where no statement has
 * them yet (the unbilled cycle, late postings, or months before your first statement).
 */
internal fun activityEntries(spends: List<Spend>, txns: List<Txn>, statements: List<Statement>): List<Spend> {
    val billedUpTo = statements.groupBy { it.cardId }.mapValues { (_, l) -> l.maxOf { it.statementEpochDay } }
    val live = spends.filter { s ->
        val billed = billedUpTo[s.cardId]
        billed == null || s.epochDay > billed || (s.review == SpendReview.NONE && s.statementTxnId == null)
    }
    return live + txns.filter { it.statementId != null }.map { it.asEntry() }
}
private val dayTimeFormat = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

/** One spend in a list. [withDate] shows the day too, for lists not grouped by day. */
@Composable
internal fun SpendRow(s: Spend, cw: CardWithDue?, cat: Category?, withDate: Boolean = false, onClick: () -> Unit) {
    val counted = TxnKind.spendSign(s.kind, s.isCredit) != 0 && s.kind != TxnKind.REFUND
    val flagged = SpendReview.flagged(s.review)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(cat?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(s.merchant.ifBlank { TxnKind.label(s.kind) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(
                        cw?.card?.let { "${it.nickname} •• ${it.last4}" },
                        if (s.source == STATEMENT_SOURCE) "Statement"
                        else Instant.ofEpochMilli(s.atMillis).atZone(ZoneId.systemDefault()).format(if (withDate) dayTimeFormat else timeFormat),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (s.review == SpendReview.MATCHED) Icon(Icons.Filled.Check, "Matches your statement", Modifier.padding(start = 6.dp).size(14.dp), tint = Good)
            }
            if (flagged) {
                Text(
                    if (s.review == SpendReview.MISSING) "Missing from statement" else "Amount differs",
                    style = MaterialTheme.typography.bodySmall, color = Amber,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (s.isCredit) "+" else "") + spendAmountText(s),
            style = MaterialTheme.typography.bodyLarge.tabular,
            color = when {
                s.isCredit -> Good
                !counted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
fun TxnRow(t: Txn, cw: CardWithDue?, cat: Category?, hPad: androidx.compose.ui.unit.Dp = 20.dp, onClick: () -> Unit) {
    val counted = TxnKind.spendSign(t.kind, t.isCredit) != 0 && t.kind != TxnKind.REFUND
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = hPad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(cat?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(t.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    cw?.card?.let { "${it.nickname} •• ${it.last4}" },
                    if (t.kind == TxnKind.SPEND) cat?.name ?: "Uncategorised" else TxnKind.label(t.kind),
                    t.note.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (t.isCredit) "+" else "") + Money.inr(t.amountPaise, true),
            style = MaterialTheme.typography.bodyLarge.tabular,
            color = when {
                t.isCredit -> Good
                !counted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
