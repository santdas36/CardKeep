package app.cardkeep.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.size
import app.cardkeep.data.Category
import app.cardkeep.data.Spend
import app.cardkeep.domain.CardWithDue
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun exportCsv(
    context: Context, uri: Uri, rows: List<Spend>, cards: Map<Long, CardWithDue>, cats: Map<Long, Category>,
) {
    fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val csv = buildString {
        appendLine("date,card,merchant,category,type,debit_credit,amount,currency,note")
        rows.sortedBy { it.atMillis }.forEach { t ->
            val c = cards[t.cardId]?.card
            appendLine(
                listOf(
                    LocalDate.ofEpochDay(t.epochDay).toString(),
                    esc(c?.let { "${it.nickname} ${it.last4}" } ?: ""),
                    esc(t.merchant),
                    esc(t.categoryId?.let { cats[it]?.name } ?: ""),
                    t.kind,
                    if (t.isCredit) "credit" else "debit",
                    java.math.BigDecimal(t.amountPaise).movePointLeft(2).toPlainString(),
                    t.currency,
                    esc(t.note),
                ).joinToString(","),
            )
        }
    }
    val ok = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }.isSuccess
    }
    Toast.makeText(context, if (ok) "Exported ${rows.size} spends" else "Export failed", Toast.LENGTH_SHORT).show()
}
