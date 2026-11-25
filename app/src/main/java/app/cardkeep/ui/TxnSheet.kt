package app.cardkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Category
import app.cardkeep.data.Ledger
import app.cardkeep.data.Statement
import app.cardkeep.data.Txn
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.ingest.Categorizer
import app.cardkeep.ingest.TxnKind
import java.time.LocalDate

/** One transaction: where it came from, what kind it is, and its category. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TxnSheet(
    txn: Txn, card: CardWithDue?, categories: List<Category>, statement: Statement?,
    onDismiss: () -> Unit, onOpenStatement: ((Long) -> Unit)?, onSave: (Long?, String?, String, String) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var cat by remember { mutableStateOf(txn.categoryId) }
    var kind by remember { mutableStateOf(txn.kind) }
    var applyAll by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf(Categorizer.merchantKey(txn.merchant)) }
    var note by remember { mutableStateOf(txn.note) }

    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(
                (if (txn.isCredit) "+" else "") + Money.inr(txn.amountPaise, true),
                style = MaterialTheme.typography.headlineMedium.tabular, color = if (txn.isCredit) Good else MaterialTheme.colorScheme.onSurface,
            )
            Text(txn.merchant, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            Text(
                listOfNotNull(Dates.long(LocalDate.ofEpochDay(txn.epochDay)), card?.card?.let { "${it.nickname} •• ${it.last4}" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (statement != null && onOpenStatement != null) {
                TextButton(onClick = { onOpenStatement(statement.id) }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null, Modifier.size(18.dp))
                    Text("  From the ${Dates.long(LocalDate.ofEpochDay(statement.statementEpochDay))} statement")
                }
            }

            Text("Type", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TxnKind.SPEND, TxnKind.REFUND, TxnKind.PAYMENT, TxnKind.CASHBACK, TxnKind.FEE).forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(TxnKind.label(k)) })
                }
            }
            Text(
                "Payments and cashback aren't counted as spending.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Category", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    FilterChip(
                        selected = cat == c.id, onClick = { cat = if (cat == c.id) null else c.id }, label = { Text(c.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color))) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().clickable { applyAll = !applyAll }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = applyAll, onCheckedChange = null)
                Text("Use this category for all matching merchants", modifier = Modifier.padding(start = 8.dp))
            }
            if (applyAll) {
                OutlinedTextField(
                    value = pattern, onValueChange = { pattern = it }, singleLine = true,
                    label = { Text("Merchant contains") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(200) }, label = { Text("Note") },
                placeholder = { Text("e.g. split with a friend, office expense") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                onClick = { onSave(cat, if (applyAll && pattern.isNotBlank()) pattern else null, kind, note) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Save") }
            extra?.invoke()
        }
    }
}

/** Applies what changed in the transaction sheet. */
suspend fun saveTxnEdits(ledger: Ledger, t: Txn, catId: Long?, pattern: String?, kind: String, note: String) {
    if (catId != t.categoryId || pattern != null) ledger.setCategory(t, catId, pattern)
    if (kind != t.kind) ledger.setKind(t.copy(categoryId = catId), kind)
    if (note.trim() != t.note) ledger.setNote(t.copy(categoryId = catId, kind = kind), note)
}
