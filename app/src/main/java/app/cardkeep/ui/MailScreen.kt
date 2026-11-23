package app.cardkeep.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cardkeep.data.Card as CardEntity
import app.cardkeep.data.ImportLog
import app.cardkeep.data.Ledger
import app.cardkeep.data.MailCrypt
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Issuers
import app.cardkeep.ingest.StatementSync
import app.cardkeep.ingest.SyncState
import app.cardkeep.ingest.SyncWorker
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Email statements. Not connected: a three-step setup. Connected: status, what needs you,
 * settings, then recent imports (the latest few, the rest behind "Show all").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    ledger: Ledger,
    log: List<ImportLog>,
    cards: List<CardEntity>,
    needsYou: List<ImportLog>,
    fixKey: String?,
    onFixKeyConsumed: () -> Unit,
    sync: SyncState,
    onSync: () -> Unit,
    onImport: () -> Unit,
    onImported: (StatementSync.Outcome) -> Unit,
    onOpenStatement: (Long) -> Unit,
    onPasswords: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mail by ledger.mailSource.collectAsStateWithLifecycle(initialValue = null)
    val connected = mail?.passwordEnc != null && !mail?.user.isNullOrBlank()
    var fixing by remember { mutableStateOf<ImportLog?>(null) }

    // Opened from an import that needs a password or a card: go straight to that fix.
    LaunchedEffect(fixKey, log) {
        val k = fixKey ?: return@LaunchedEffect
        log.firstOrNull { it.key == k }?.let { fixing = it; onFixKeyConsumed() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email statements") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (connected) {
                StatusCard(mail!!.user, mail!!.lastSyncAt, mail!!.lastResult, sync, onSync, onImport)
            }
            NeedsYouSection(needsYou) { fixing = it }
            if (!connected) {
                Setup(ledger, cards, onConnected = onSync, onImport = onImport, onPasswords = onPasswords)
            } else {
                // Settings first: the import list grows every month and would push them out of reach.
                Advanced(ledger, cards, onPasswords)
                RecentImports(log, onOpenStatement, onRetry = { scope.launch { ledger.retryFailed(); onSync() } })
            }
        }
    }

    fixing?.let { e ->
        ImportFixDialog(
            entry = e, cards = cards,
            onDismiss = { fixing = null },
            onSkip = { fixing = null; scope.launch { ledger.dismiss(e.key) } },
            onResult = { r -> fixing = null; onImported(r) },
        )
    }
}


@Composable
private fun StatusCard(user: String, lastSyncAt: Long, lastResult: String, sync: SyncState, onSync: () -> Unit, onImport: () -> Unit) {
    Panel {
        Column {
            Text("Connected", style = MaterialTheme.typography.labelMedium, color = Good)
            Text(user, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(6.dp))
            if (sync.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(sync.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                }
            } else {
                Text(
                    (if (lastSyncAt == 0L) "Not synced yet" else "Synced ${android.text.format.DateUtils.getRelativeTimeSpanString(lastSyncAt)}") +
                        (lastResult.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Checks again automatically once a day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSync, enabled = !sync.running) {
                    Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp)); Text("  Sync now")
                }
                OutlinedButton(onClick = onImport, enabled = !sync.running) {
                    Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Text("  Import PDF")
                }
            }
        }
    }
}

/** First run: connect, confirm banks, optional PDF details. */
@Composable
private fun Setup(ledger: Ledger, cards: List<CardEntity>, onConnected: () -> Unit, onImport: () -> Unit, onPasswords: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by rememberSaveable { mutableStateOf("") }
    // Plain remember: a saveable value would sit in plaintext in the saved-instance Bundle.
    var password by remember { mutableStateOf("") }
    var extra by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val banks = cards.map { it.issuer }.distinct().map(Issuers::byName)
    val known = banks.filter { it.senders.isNotEmpty() }
    val unknown = banks.filter { it.senders.isEmpty() }

    Text(
        "CardKeep reads statement emails from your bank, read-only, and unlocks the PDFs on this phone. Nothing is sent anywhere else.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Step(1, "Connect Gmail") {
        Text(
            "Gmail needs an app password (not your normal one). Create one named \"CardKeep\" and paste it here. Needs 2-Step Verification.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }
        }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Text("  Open Google app passwords") }
        OutlinedTextField(
            user, { user = it.trim() }, label = { Text("Gmail address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            password, { password = it }, label = { Text("App password") }, placeholder = { Text("16 letters") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
    }

    Step(2, "Banks to look for") {
        Text(
            when {
                banks.isEmpty() -> "Add a card first. Senders come from your cards' banks."
                known.isEmpty() -> "No known statement senders for your banks yet. Add the sender address below."
                else -> known.joinToString(", ") { it.name } + (if (unknown.isNotEmpty()) ". Add senders for ${unknown.joinToString(", ") { it.name }} below." else "")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            extra, { extra = it }, label = { Text("Extra sender addresses (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
    }

    Step(3, "PDF passwords (optional)") {
        Text(
            "Most banks lock statements with your name and birth date. Add them once and CardKeep unlocks them for you. You can also enter a password when a statement arrives.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Panel(padded = false, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            ListRow(Icons.Outlined.Key, "Statement passwords", chevron = true, onClick = onPasswords)
        }
    }

    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Connected")) Good else Danger) }
    Button(
        enabled = !busy && user.contains('@') && password.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                busy = true
                status = "Connecting…"
                val r = StatementSync.test("imap.gmail.com", 993, user, password)
                if (r.startsWith("Connected")) {
                    ledger.saveMailSource(user, password, "imap.gmail.com", 993, extra, 120, null)
                    SyncWorker.enable(context)
                    password = ""
                    onConnected()
                }
                status = r
                busy = false
            }
        },
    ) { Text(if (busy) "Connecting…" else "Connect and sync") }

    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Text("  Or import a statement PDF")
    }
}

@Composable
private fun Step(n: Int, title: String, content: @Composable () -> Unit) {
    SectionHeader("$n · $title")
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** Imports by month; duplicates and skipped ones folded away. */
@Composable
private fun RecentImports(log: List<ImportLog>, onOpenStatement: (Long) -> Unit, onRetry: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val quiet = setOf("duplicate", "dismissed")
    val relevant = (if (showAll) log else log.filter { it.outcome !in quiet && it.outcome !in FIXABLE }).take(60)
    val shown = if (expanded) relevant else relevant.take(RECENT_PREVIEW)
    val hidden = log.count { it.outcome in quiet }
    if (log.isEmpty()) return

    Row(verticalAlignment = Alignment.Bottom) {
        SectionHeader("Recent imports", Modifier.weight(1f))
        if (log.any { it.outcome in setOf("unparsed", "error") }) TextButton(onClick = onRetry) { Text("Retry failed") }
    }
    if (shown.isEmpty()) {
        Text("Nothing new imported yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Panel(padded = false) {
            Column {
                val fmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
                shown.groupBy { YearMonth.from(Instant.ofEpochMilli(it.receivedAt).atZone(ZoneId.systemDefault())) }.forEach { (ym, list) ->
                    Text(
                        ym.format(fmt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    list.forEach { e ->
                        val sid = e.statementId
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (sid != null) Modifier.clickable { onOpenStatement(sid) } else Modifier)
                                .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Dates.short(Instant.ofEpochMilli(e.receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()) + " · " + e.detail,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                outcomeLabel(e.outcome), style = MaterialTheme.typography.labelMedium,
                                color = when (e.outcome) {
                                    "imported" -> Good; "needs_review" -> Amber
                                    "duplicate", "dismissed" -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> Danger
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    Row {
        if (relevant.size > RECENT_PREVIEW) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${relevant.size}") }
        }
        if (hidden > 0 && (expanded || relevant.size <= RECENT_PREVIEW)) {
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide duplicates and skipped" else "Show $hidden duplicates and skipped") }
        }
    }
}

private const val RECENT_PREVIEW = 6

/** Account, server, senders, look-back and disconnect: rarely needed, so folded. */
@Composable
private fun Advanced(ledger: Ledger, cards: List<CardEntity>, onPasswords: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    // Saveable like the fields it guards, so rotation keeps your edits instead of reloading from the DB.
    // The app password is the exception: plain remember keeps it out of the saved-instance Bundle, so a
    // rotation only clears that one field (blank means "keep the saved password").
    var loaded by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hasPassword by rememberSaveable { mutableStateOf(false) }
    var changingPassword by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("imap.gmail.com") }
    var extra by rememberSaveable { mutableStateOf("") }
    var lookback by rememberSaveable { mutableStateOf("120") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(open) {
        if (!open || loaded) return@LaunchedEffect
        ledger.mailSourceOnce()?.let { m ->
            user = m.user; host = m.host; extra = m.extraSenders; lookback = m.lookbackDays.toString()
            hasPassword = m.passwordEnc != null
        }
        loaded = true
    }

    SectionHeader("Settings")
    Panel(padded = false) {
        ListRow(Icons.Outlined.Key, "Statement passwords", "Name, birth date, PAN, used to unlock PDFs", chevron = true, onClick = onPasswords)
        RowDivider()
        ListRow(
            Icons.Outlined.Tune, "Account and advanced", "Email, server, senders, how far back", onClick = { open = !open },
            action = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        AnimatedVisibility(open && loaded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it.trim() }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
                if (hasPassword && !changingPassword) {
                    // Saved (encrypted on this phone); never shown back. Change opens an empty field.
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("App password", style = MaterialTheme.typography.bodyMedium)
                            Text("Saved  ••••••••••••••••", style = MaterialTheme.typography.bodySmall, color = Good)
                        }
                        TextButton(onClick = { changingPassword = true }) { Text("Change") }
                    }
                } else {
                    OutlinedTextField(
                        password, { password = it }, label = { Text(if (hasPassword) "New app password" else "App password") },
                        placeholder = { Text("16 letters") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = if (hasPassword) {
                            { TextButton(onClick = { password = ""; changingPassword = false }) { Text("Cancel") } }
                        } else null,
                    )
                }
                OutlinedTextField(host, { host = it.trim() }, label = { Text("IMAP server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    extra, { extra = it }, label = { Text("Extra sender addresses (comma-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val known = cards.map { it.issuer }.distinct().map(Issuers::byName).filter { it.senders.isNotEmpty() }
                        Text(if (known.isEmpty()) "No built-in senders for your banks" else "Built in: " + known.joinToString(", ") { it.name })
                    },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    lookback, { lookback = it.filter(Char::isDigit).take(3) }, label = { Text("Look back (days)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && user.contains('@'), onClick = {
                        scope.launch {
                            ledger.saveMailSource(user, password.ifBlank { null }, host, 993, extra, lookback.toIntOrNull()?.coerceIn(7, 730) ?: 120, null)
                            if (password.isNotBlank()) hasPassword = true
                            password = ""; changingPassword = false; status = "Saved."
                        }
                    }) { Text("Save") }
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true; status = "Connecting…"
                            val pw = password.ifBlank { MailCrypt.dec(ledger.mailSourceOnce()?.passwordEnc).orEmpty() }
                            status = StatementSync.test(host, 993, user, pw)
                            busy = false
                        }
                    }) { Text("Test") }
                    TextButton(onClick = { confirmRemove = true }) { Text("Disconnect", color = Danger) }
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Disconnect email?",
            body = "Deletes the saved app password from this phone and stops syncing. Imported statements and your PDF password details stay. Also revoke the app password in your Google account.",
            confirmLabel = "Disconnect",
            onDismiss = { confirmRemove = false },
            onConfirm = { confirmRemove = false; scope.launch { ledger.disconnectMail(); SyncWorker.disable(context) } },
        )
    }
}

private fun outcomeLabel(o: String) = when (o) {
    "imported" -> "Imported"
    "needs_review" -> "Review"
    "duplicate" -> "Duplicate"
    "dismissed" -> "Skipped"
    "locked" -> "Locked"
    "unmatched" -> "No card"
    "unparsed" -> "Unreadable"
    else -> "Error"
}
