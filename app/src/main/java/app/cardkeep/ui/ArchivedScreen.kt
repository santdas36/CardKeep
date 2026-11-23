package app.cardkeep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import java.time.Instant
import java.time.ZoneId

/** Archived (closed / deactivated) cards: open for history, restore, or delete for good. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(
    cards: List<CardWithDue>,
    onOpen: (Long) -> Unit,
    onRestore: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var confirm by remember { mutableStateOf<CardWithDue?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived cards") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 32.dp)) {
            if (cards.isEmpty()) item {
                Text(
                    "No archived cards",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            } else item {
                SectionHeader("${cards.size} archived")
                Panel(padded = false) {
                    cards.forEachIndexed { i, cw ->
                        val c = cw.card
                        if (i > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(c.id) }.heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BankBadge(c.issuer, size = 34.dp)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(c.nickname, style = MaterialTheme.typography.bodyLarge)
                                val on = c.archivedAt?.let { Dates.short(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) }
                                Text(
                                    "${c.issuer} · •• ${c.last4}" + (on?.let { " · archived $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRestore(c.id) }) { Icon(Icons.Outlined.Unarchive, "Restore ${c.nickname}") }
                            IconButton(onClick = { confirm = cw }) { Icon(Icons.Outlined.Delete, "Delete ${c.nickname}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { cw ->
        ConfirmDialog(
            title = "Delete ${cw.card.nickname}?",
            body = "The card, its stored number and CVV, statements and payment history are removed from this phone. This can't be undone.",
            onDismiss = { confirm = null },
            onConfirm = { confirm = null; onDelete(cw.card.id) },
        )
    }
}