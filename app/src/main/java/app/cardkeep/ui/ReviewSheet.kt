package app.cardkeep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Statement
import app.cardkeep.domain.CardWithDue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Every statement that didn't add up, oldest first, each with what's wrong. Tapping one opens it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(statements: List<Statement>, cardById: Map<Long, CardWithDue>, onOpen: (Long) -> Unit, onDismiss: () -> Unit) {
    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    val month = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Statements to check", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            Panel(padded = false) {
                statements.sortedBy { it.statementEpochDay }.forEachIndexed { i, s ->
                    if (i > 0) RowDivider()
                    val card = cardById[s.cardId]?.card
                    ListRow(
                        icon = null,
                        leading = { BankBadge(card?.issuer.orEmpty(), size = 34.dp) },
                        title = (card?.let { "${it.nickname} •• ${it.last4} · " } ?: "") + LocalDate.ofEpochDay(s.statementEpochDay).format(month),
                        subtitle = s.note.ifBlank { null },
                        chevron = true,
                        onClick = { onDismiss(); onOpen(s.id) },
                    )
                }
            }
        }
    }
}
