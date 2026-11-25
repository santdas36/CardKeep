package app.cardkeep.ui

import app.cardkeep.data.CardKind
import app.cardkeep.data.hasDues
import app.cardkeep.data.isCredit
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.cardkeep.data.CardSecret
import app.cardkeep.data.Ledger
import app.cardkeep.data.Payment
import app.cardkeep.data.Repo
import app.cardkeep.data.Statement
import app.cardkeep.domain.Billing
import app.cardkeep.domain.CardNumbers
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.security.Auth
import app.cardkeep.security.SecretDraft
import app.cardkeep.security.Vault
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val REVEAL_SECONDS = 15
private const val CLIPBOARD_CLEAR_MS = 30_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    cw: CardWithDue?,
    payments: List<Payment>,
    repo: Repo,
    statements: List<Statement> = emptyList(),
    onOpenStatement: (Long) -> Unit = {},
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()

    var secret by remember { mutableStateOf<CardSecret?>(null) }
    var revealed by remember { mutableStateOf<SecretDraft?>(null) }
    var revealLeft by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var payDialog by remember { mutableStateOf(false) }
    var balanceDialog by remember { mutableStateOf(false) }
    var confirmDeletePayment by remember { mutableStateOf<Payment?>(null) }
    var pinFull by remember { mutableStateOf<List<app.cardkeep.data.Card>?>(null) } // pinned cards, when a 4th is pinned

    val cardId = cw?.card?.id
    LaunchedEffect(cardId) { if (cardId != null) secret = repo.secret(cardId) }
    LaunchedEffect(revealed) {
        if (revealed != null) {
            revealLeft = REVEAL_SECONDS
            while (revealLeft > 0) { delay(1000); revealLeft-- }
            revealed = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { revealed = null }

    if (cw == null) {
        Scaffold { Column(Modifier.padding(it)) {} }
        return
    }
    val c = cw.card
    val d = cw.due
    val s = secret
    val hasSecrets = s != null && (s.numberEnc != null || s.expiryEnc != null || s.cvvEnc != null || s.holderEnc != null)
    val stmts = statements.filter { it.cardId == c.id }.sortedByDescending { it.statementEpochDay }
    val latest = stmts.firstOrNull()
    val minDue = latest?.takeIf { it.dueEpochDay == d.dueDate.toEpochDay() }?.minDuePaise

    suspend fun unlock(title: String): SecretDraft? {
        val sec = secret ?: return null
        return try {
            Auth.unlockVault(activity, title, c.nickname) { withContext(Dispatchers.IO) { Vault.decrypt(sec) } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = if (e is Vault.KeyLost) e.message else "Couldn't unlock card details: ${e.javaClass.simpleName}"
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            null
        }
    }

    fun reveal() = scope.launch { unlock("Reveal card details")?.let { revealed = it } }
    // Leaving with the card revealed: go back at once and let the home card turn it face up as it lands,
    // so the flip and the move happen together (only the home copy is drawn during the move).
    fun back() {
        revealed?.let { r ->
            FlipBack.start(FlipBack.Pending(c.id, r, StoredFields(s?.numberEnc != null, s?.expiryEnc != null, s?.cvvEnc != null, s?.holderEnc != null)))
        }
        revealed = null
        onBack()
    }
    BackHandler(enabled = revealed != null) { back() }
    val archived = c.archivedAt != null

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {},
                navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit card") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (!archived) DropdownMenuItem(
                            text = { Text(if (c.pinned) "Unpin from stack" else "Pin to stack") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = {
                                menu = false
                                scope.launch {
                                    if (repo.setPinned(c, !c.pinned)) haptic(Haptic.Confirm)
                                    else { haptic(Haptic.Reject); pinFull = repo.activeCards(includeAll = true).filter { it.pinned } }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (archived) "Restore card" else "Archive card") },
                            leadingIcon = { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                            onClick = {
                                menu = false
                                scope.launch {
                                    repo.setArchived(c.id, !archived)
                                    Toast.makeText(context, if (archived) "${c.nickname} restored" else "${c.nickname} archived", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        if (c.isCredit) DropdownMenuItem(
                            text = { Text("Update balance") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; balanceDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete card", color = Danger) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Danger) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
    ) { pad ->
        pinFull?.let { pinned ->
            ReplacePinDialog(c, pinned, onReplace = { id ->
                pinFull = null
                scope.launch { haptic(if (repo.setPinned(c, true, id)) Haptic.Confirm else Haptic.Reject) }
            }, onDismiss = { pinFull = null })
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 40.dp),
        ) {
            if (archived) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Archived", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { scope.launch { repo.setArchived(c.id, false) } }) { Text("Restore") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Card turns over to show the decrypted details; tap any detail to copy it.
            val progress by animateFloatAsState(
                if (revealed == null) 0f else revealLeft / REVEAL_SECONDS.toFloat(), tween(1000), label = "reveal",
            )
            FlipCard(
                cw, revealed,
                StoredFields(s?.numberEnc != null, s?.expiryEnc != null, s?.cvvEnc != null, s?.holderEnc != null),
                progress,
                onCopy = { label, value -> copySensitive(context, label, value) },
                // The tapped card from the home stack grows into this one (and shrinks back on pop).
                modifier = Modifier.sharedCard(c.id),
            )
            Spacer(Modifier.height(18.dp))

            // Quick actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuickAction(
                    if (revealed == null) Icons.Outlined.Fingerprint else Icons.Outlined.VisibilityOff,
                    if (revealed == null) "Reveal" else "Hide ${revealLeft}s",
                    enabled = hasSecrets,
                ) { if (revealed == null) reveal() else revealed = null }
                if (c.isCredit) {
                    if (!archived) QuickAction(Icons.Outlined.CheckCircle, "Paid", enabled = c.billingSet && !d.lastCyclePaid) { payDialog = true }
                    QuickAction(Icons.AutoMirrored.Outlined.ReceiptLong, "Statements", enabled = latest != null) {
                        latest?.let { onOpenStatement(it.id) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            if (!hasSecrets) {
                Panel {
                    Text(
                        "Only the last four digits are saved. Add the full number, expiry and CVV (encrypted on this phone) to reveal and copy them from the card.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onEdit) { Text("Add card number") }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Due hero
            if (!archived && c.hasDues) DueHero(cw, minDue, onPay = { payDialog = true })
            else if (!archived && c.isCredit) {
                // Added without a due date: no cycle to show, so ask for one.
                Panel {
                    Text("No due date yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add the due date to get reminders. Importing a statement sets it too.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onEdit) { Text("Add due date") }
                }
            }

            // Credit limit
            if (c.isCredit && c.limitPaise > 0) {
                SectionHeader("Credit limit")
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UtilRing(cw.utilization, Modifier.size(84.dp))
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            KeyValue("Used", Money.inr(c.outstandingPaise))
                            KeyValue("Available", Money.inr((c.limitPaise - c.outstandingPaise).coerceAtLeast(0)))
                            KeyValue("Limit", Money.inr(c.limitPaise))
                        }
                    }
                }
            }

            CardSpendLog(c, latest?.statementEpochDay)

            // Rewards, from imported statements.
            val pointsStmt = stmts.firstOrNull { it.rewardPointsBalance != null || it.rewardPointsEarned != null }
            val cashbackTotal = stmts.mapNotNull { it.cashbackPaise }.takeIf { it.isNotEmpty() }?.sum()
            if (pointsStmt != null || cashbackTotal != null) {
                SectionHeader("Rewards")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pointsStmt != null) {
                        val unit = (pointsStmt.rewardUnit ?: "points").replaceFirstChar(Char::uppercase)
                        InfoTile(
                            if (pointsStmt.rewardPointsBalance != null) "$unit balance" else "$unit this cycle",
                            "%,d".format(pointsStmt.rewardPointsBalance ?: pointsStmt.rewardPointsEarned) +
                                (pointsStmt.rewardPointsEarned?.takeIf { pointsStmt.rewardPointsBalance != null }?.let { "  (+%,d)".format(it) } ?: ""),
                            Modifier.weight(1f),
                        )
                    }
                    if (cashbackTotal != null) {
                        InfoTile(
                            if (stmts.count { it.cashbackPaise != null } > 1) "Cashback (${stmts.count { it.cashbackPaise != null }} statements)" else "Cashback",
                            Money.inr(cashbackTotal, true), Modifier.weight(1f),
                        )
                    }
                }
            }

            // Billing grid (credit) or just the basics (debit / prepaid)
            SectionHeader(if (c.isCredit) "Billing" else "Card")
            val leads = Billing.parseLeads(c.reminderLeads)
            if (c.hasDues) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoTile("Statement", "${ordinal(c.statementDay)} of month", Modifier.weight(1f))
                    InfoTile("Due", "${c.dueOffsetDays} days later", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (c.isCredit) InfoTile(
                    "Reminders",
                    if (c.billingSet) reminderLeadsSummary(leads) else "Off until due date is set",
                    Modifier.weight(1f),
                ) else InfoTile("Type", CardKind.label(c.kind), Modifier.weight(1f))
                Panel(Modifier.weight(1f)) {
                    Text("Network", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    NetworkMark(c.network)
                }
            }

            // Statements (credit cards only; debit and prepaid cards have none)
            if (c.isCredit && stmts.isNotEmpty()) {
                SectionHeader("Statements")
                Panel(padded = false) {
                    stmts.take(12).forEachIndexed { i, st ->
                        if (i > 0) RowDivider()
                        val ok = st.status == Ledger.STATUS_OK
                        ListRow(
                            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                            iconTint = if (ok) MaterialTheme.colorScheme.primary else Amber,
                            title = Dates.long(LocalDate.ofEpochDay(st.statementEpochDay)),
                            subtitle = if (ok) "Due " + (st.dueEpochDay?.let { Dates.short(LocalDate.ofEpochDay(it)) } ?: "—") else "Needs review",
                            trailing = st.totalDuePaise?.let { Money.inr(it, true) } ?: "—",
                            onClick = { onOpenStatement(st.id) },
                        )
                    }
                }
            }

            // Payments
            val mine = payments.filter { it.cardId == c.id }
            if (mine.isNotEmpty()) {
                SectionHeader("Payments")
                Panel(padded = false) {
                    mine.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        ListRow(
                            icon = Icons.Outlined.CheckCircle,
                            iconTint = Good,
                            title = if (p.amountPaise > 0) Money.inr(p.amountPaise, true) else "Settled, nothing due",
                            subtitle = "Paid ${Dates.short(LocalDate.ofEpochDay(p.paidOnEpochDay))} · for due ${Dates.short(LocalDate.ofEpochDay(p.dueEpochDay))}",
                            trailing = null,
                            action = {
                                IconButton(onClick = { confirmDeletePayment = p }) {
                                    Icon(Icons.Outlined.Delete, "Delete payment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
            }

            if (c.notes.isNotBlank()) {
                SectionHeader("Notes")
                Panel { Text(c.notes, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${c.nickname}?",
            body = "The card, its stored number and CVV, statements and payment history are removed from this phone. This can't be undone.",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; scope.launch { repo.delete(c.id); onBack() } },
        )
    }

    confirmDeletePayment?.let { p ->
        ConfirmDialog(
            title = "Delete this payment?",
            body = "The ${if (p.amountPaise > 0) Money.inr(p.amountPaise, true) + " " else ""}payment for the due on ${Dates.short(LocalDate.ofEpochDay(p.dueEpochDay))} is removed from this phone, and its reminders may come back. This can't be undone.",
            onDismiss = { confirmDeletePayment = null },
            onConfirm = { confirmDeletePayment = null; scope.launch { repo.deletePayment(p) } },
        )
    }

    if (payDialog) {
        // Same prefill as the statement screen: when this due has a statement, what's left of its total;
        // otherwise the card's outstanding (which payments already reduce).
        val dueDay = d.dueDate.toEpochDay()
        val paidForDue = payments.filter { it.cardId == c.id && it.dueEpochDay == dueDay }.sumOf { it.amountPaise }
        val stmtLeft = latest?.takeIf { it.dueEpochDay == dueDay }?.totalDuePaise?.let { it - paidForDue }
        MarkPaidSheet(
            dueLabel = Dates.weekday(d.dueDate),
            total = stmtLeft ?: c.outstandingPaise,
            minimum = minDue,
            onDismiss = { payDialog = false },
            onConfirm = { paise ->
                payDialog = false
                scope.launch { repo.markPaid(c.id, d.dueDate.toEpochDay(), paise) }
            },
        )
    }

    if (balanceDialog) {
        AmountSheet(
            title = "Outstanding balance",
            note = "What the card owes right now. Statements update this automatically.",
            initialPaise = c.outstandingPaise,
            onDismiss = { balanceDialog = false },
            onConfirm = { paise ->
                balanceDialog = false
                scope.launch { repo.setOutstanding(c.id, paise) }
            },
        )
    }
}

@Composable
private fun DueHero(cw: CardWithDue, minDue: Long?, onPay: () -> Unit) {
    val d = cw.due
    val c = cw.card
    val accent = when {
        d.overdue || (!d.lastCyclePaid && d.daysLeft <= 2) -> Danger
        !d.lastCyclePaid && d.daysLeft <= 5 -> Amber
        d.lastCyclePaid -> Good
        else -> MaterialTheme.colorScheme.primary
    }
    Panel {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        d.overdue -> "Overdue"
                        d.lastCyclePaid -> "All paid"
                        else -> "Amount due"
                    },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(if (d.lastCyclePaid) "Next ${Dates.short(d.dueDate)}" else Dates.relative(d.daysLeft).replaceFirstChar { it.uppercase() }, accent)
            }
            Text(
                Money.inr(c.outstandingPaise, true),
                style = MaterialTheme.typography.displaySmall.tabular,
            )
            Text(
                buildString {
                    append(if (d.lastCyclePaid) "Statement ${Dates.short(d.statementDate)}" else "Pay by ${Dates.weekday(d.dueDate)}")
                    if (minDue != null && !d.lastCyclePaid) append("  ·  Minimum ${Money.inr(minDue, true)}")
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!d.lastCyclePaid) {
                // How much of the grace period is gone.
                val total = ChronoUnit.DAYS.between(d.statementDate, d.dueDate).coerceAtLeast(1)
                val gone = ChronoUnit.DAYS.between(d.statementDate, LocalDate.now()).coerceIn(0, total)
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { gone.toFloat() / total },
                    color = accent, trackColor = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(Dates.short(d.statementDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(Dates.short(d.dueDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = onPay, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (c.outstandingPaise <= 0) "Mark as settled" else "Mark as paid") }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

/** Utilisation ring: green under 30%, amber to 70%, red above. */
@Composable
private fun UtilRing(fraction: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction, tween(600), label = "ring")
    val color = when {
        fraction >= 0.7f -> Danger
        fraction >= 0.3f -> Amber
        else -> Good
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 9.dp.toPx()
            val inset = w / 2
            val sz = Size(size.width - w, size.height - w)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
            drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium.tabular)
            Text("used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val CLIP_TOKEN = "app.cardkeep.CLIP_TOKEN"

/**
 * Copies with the sensitive flag (hidden from clipboard previews) and clears after 30 s, but only if
 * the clipboard still holds this copy: a random token in the clip's extras marks it as ours, so
 * something the user copied since (here or in another app) is left alone.
 */
private fun copySensitive(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val token = java.util.UUID.randomUUID().toString()
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        putString(CLIP_TOKEN, token)
    }
    cm.setPrimaryClip(clip)
    Toast.makeText(context, "$label copied!", Toast.LENGTH_SHORT).show()
    Handler(Looper.getMainLooper()).postDelayed({
        // While the app is in the background Android 10+ hides the clipboard (reads return null).
        // Then we can't tell whose clip it is, so clear anyway: a card number must not linger.
        val current = runCatching { cm.primaryClipDescription }.getOrNull()
        val replaced = current != null && current.extras?.getString(CLIP_TOKEN) != token
        if (!replaced) {
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, CLIPBOARD_CLEAR_MS)
}
