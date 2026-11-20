package app.duely.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.duely.data.Card as CardEntity
import app.duely.data.ImportLog
import app.duely.data.isCredit
import app.duely.ingest.StatementSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcomes the user can fix on the phone: a PDF password, or which card it belongs to. */
val FIXABLE = setOf("locked", "unmatched")

/** "Needs you": one row per email we couldn't finish, each opening its own fix. */
@Composable
fun NeedsYouSection(items: List<ImportLog>, onFix: (ImportLog) -> Unit) {
    if (items.isEmpty()) return
    SectionHeader(if (items.size == 1) "1 statement needs you" else "${items.size} statements need you", color = Amber)
    Panel(padded = false, color = Amber.copy(alpha = 0.12f)) {
        items.forEachIndexed { i, e ->
            if (i > 0) RowDivider()
            ListRow(
                if (e.outcome == "locked") Icons.Outlined.Lock else Icons.Outlined.CreditCard,
                e.subject.ifBlank { "(no subject)" },
                if (e.outcome == "locked") "Locked. Enter its password" else "Pick which card it's for",
                iconTint = Amber, chevron = true, onClick = { onFix(e) },
            )
        }
    }
}

/** Password or card picker for one kept PDF. Retries on the phone; nothing is fetched again. */
@Composable
fun ImportFixDialog(
    entry: ImportLog,
    cards: List<CardEntity>,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onResult: (StatementSync.Outcome) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locked = entry.outcome == "locked"
    var password by remember { mutableStateOf("") }
    var rememberPw by remember { mutableStateOf(true) }
    var cardId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    EditSheet(
        title = if (locked) "Unlock statement" else "Which card?",
        note = entry.subject.ifBlank { "(no subject)" },
        onDismiss = { if (!busy) onDismiss() },
        confirmLabel = if (busy) "Trying…" else if (locked) "Unlock" else "Import",
        confirmEnabled = !busy && (if (locked) password.isNotBlank() else cardId != null),
        onConfirm = {
            busy = true
            scope.launch {
                val r = StatementSync.fix(context, entry.key, password.takeIf { locked }, cardId, rememberPw)
                busy = false
                if (r.code in FIXABLE && r.code == entry.outcome) error = r.message else onResult(r)
            }
        },
    ) {
        if (locked) {
            OutlinedTextField(
                password, { password = it; error = null }, label = { Text("PDF password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Usually in the bank's email, e.g. first 4 letters of your name + DDMM of birth.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().clickable { rememberPw = !rememberPw }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberPw, null)
                Text("Remember for next month", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            // Statements only belong to credit cards; debit and prepaid cards have none.
            val creditCards = cards.filter { it.isCredit }
            if (creditCards.isEmpty()) {
                Text(
                    "Add a credit card first, then pick it here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else Panel(padded = false) {
                creditCards.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { cardId = c.id; error = null }.heightIn(min = 60.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BankBadge(c.issuer, size = 34.dp)
                        Text("${c.nickname} •• ${c.last4}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        RadioButton(cardId == c.id, null)
                    }
                }
            }
            if (entry.sender.isNotBlank()) {
                Text(
                    "Statements from this sender will go to this card from now on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Danger) }
        InlineAction("Skip this statement", onSkip)
    }
}
/** Opens the system file picker for a PDF and runs it through the import pipeline. */
@Composable
fun rememberPdfImporter(onOutcome: (StatementSync.Outcome) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { onOutcome(importUri(context, uri)) }
    }
    return { launcher.launch(arrayOf("application/pdf")) }
}

/** Reads a content URI (picked or shared) and imports it. The PDF only lives in memory and the encrypted DB. */
suspend fun importUri(context: Context, uri: Uri): StatementSync.Outcome {
    val read = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "statement.pdf"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty")
            name to bytes
        }.getOrNull()
    } ?: return StatementSync.Outcome("error", "Couldn't read that file.")
    if (read.second.size > 20_000_000) return StatementSync.Outcome("error", "That PDF is too large.")
    return StatementSync.importFile(context, read.first, read.second)
}
