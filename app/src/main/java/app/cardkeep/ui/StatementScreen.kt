package app.cardkeep.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cardkeep.data.Category
import app.cardkeep.data.Ledger
import app.cardkeep.data.Payment
import app.cardkeep.data.Repo
import app.cardkeep.data.Statement
import app.cardkeep.data.Txn
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.ingest.TxnKind
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** A card's statements, one page each; swipe for other months. Opens on [statementId]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementScreen(
    statementId: Long,
    ledger: Ledger,
    repo: Repo,
    cards: List<CardWithDue>,
    categories: List<Category>,
    statements: List<Statement>,
    payments: List<Payment>,
    onBack: () -> Unit,
    onOpenStatement: (Long) -> Unit = {},
) {
    val start = statements.firstOrNull { it.id == statementId }
    val cardId = start?.cardId
    val pages = remember(statements, cardId) { statements.filter { it.cardId == cardId }.sortedBy { it.statementEpochDay } }
    if (start == null || pages.isEmpty()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Statement") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { }
        return
    }
    val cw = cards.firstOrNull { it.card.id == cardId }
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.id == statementId }.coerceAtLeast(0)) { pages.size }
    val scope = rememberCoroutineScope()
    val current = pages.getOrNull(pager.currentPage) ?: start
    var menu by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var editHeader by remember { mutableStateOf(false) }
    var editLine by remember { mutableStateOf<Txn?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteLine by remember { mutableStateOf<Txn?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(LocalDate.ofEpochDay(current.statementEpochDay).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)))
                        cw?.let { Text("${it.card.nickname} •• ${it.card.last4}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous statement")
                    }
                    IconButton(enabled = pager.currentPage < pages.size - 1, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next statement")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit totals") }, onClick = { menu = false; editHeader = true })
                        DropdownMenuItem(text = { Text("Add line") }, onClick = { menu = false; editLine = newLine(current, credit = false) })
                        DropdownMenuItem(text = { Text("Read again") }, onClick = {
                            menu = false
                            if (cw != null) scope.launch { ledger.reparse(current.id, cw.card.issuer) }
                        })
                        DropdownMenuItem(text = { Text("View extracted text") }, onClick = { menu = false; showText = true })
                        DropdownMenuItem(text = { Text("Delete statement") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
    ) { pad ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(pad), key = { pages[it].id }) { i ->
            // Oldest other statement (any card) that still needs a look: offered once this one is settled.
            val next = statements.filter { it.status == Ledger.STATUS_REVIEW && it.id != pages[i].id }.minByOrNull { it.statementEpochDay }
            StatementPage(
                pages[i], ledger, repo, cw, categories, payments,
                onEditTotals = { editHeader = true }, onAddLine = { credit -> editLine = newLine(pages[i], credit) },
                nextReview = next, reviewLeft = statements.count { it.status == Ledger.STATUS_REVIEW && it.id != pages[i].id },
                onNext = { next?.let { onOpenStatement(it.id) } },
            )
        }
    }

    if (showText) {
        AlertDialog(
            onDismissRequest = { showText = false },
            title = { Text("Extracted text") },
            text = {
                SelectionContainer {
                    Text(
                        current.rawText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showText = false }) { Text("Close") } },
        )
    }

    if (editHeader) {
        val st = current
        var due by remember(st.id) { mutableStateOf(st.dueEpochDay?.let(LocalDate::ofEpochDay)) }
        var total by remember(st.id) { mutableStateOf(st.totalDuePaise?.let(Money::toInput) ?: "") }
        var min by remember(st.id) { mutableStateOf(st.minDuePaise?.let(Money::toInput) ?: "") }
        var opening by remember(st.id) { mutableStateOf(st.openingPaise?.let(Money::toInput) ?: "") }
        // Blank clears a total; anything else must parse, or Save stays off (never silently cleared).
        val valid = listOf(total, min, opening).all { it.isBlank() || Money.parse(it) != null }
        EditSheet(
            title = "Statement totals", note = "As printed on the bank's statement.",
            onDismiss = { editHeader = false }, confirmLabel = "Save", confirmEnabled = valid,
            onConfirm = {
                editHeader = false
                scope.launch {
                    ledger.editStatementHeader(
                        st.id, due?.toEpochDay(),
                        total.takeIf { it.isNotBlank() }?.let(Money::parse),
                        min.takeIf { it.isNotBlank() }?.let(Money::parse),
                        opening.takeIf { it.isNotBlank() }?.let(Money::parse),
                    )
                }
            },
        ) {
            DateField("Due date", due, { due = it }, initialPick = LocalDate.ofEpochDay(st.statementEpochDay).plusDays(20))
            MoneyField("Total due", total) { total = it }
            MoneyField("Minimum due", min) { min = it }
            MoneyField("Opening balance", opening) { opening = it }
        }
    }

    editLine?.let { t ->
        LineDialog(
            t,
            onDismiss = { editLine = null },
            onSave = { nt -> editLine = null; scope.launch { ledger.saveLine(nt) } },
            onDelete = if (t.id != 0L) ({ editLine = null; deleteLine = t }) else null,
        )
    }
    deleteLine?.let { t ->
        ConfirmDeleteLine(t, onDismiss = { deleteLine = null }, onConfirm = { deleteLine = null; scope.launch { ledger.deleteLine(t) } })
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this statement?",
            body = "Its transactions are removed from this phone too. The email stays in your inbox. This can't be undone.",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val id = current.id
                // Always leave: once the statement this screen was opened on is gone, it has nothing to anchor to.
                scope.launch { ledger.deleteStatement(id); onBack() }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StatementPage(
    st: Statement, ledger: Ledger, repo: Repo, cw: CardWithDue?, categories: List<Category>, payments: List<Payment>,
    onEditTotals: () -> Unit, onAddLine: (credit: Boolean) -> Unit,
    nextReview: Statement? = null, reviewLeft: Int = 0, onNext: () -> Unit = {},
) {
    val lines by remember(st.id) { ledger.linesFor(st.id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val catById = categories.associateBy { it.id }
    var open by remember { mutableStateOf<Txn?>(null) }
    var editLine by remember { mutableStateOf<Txn?>(null) }
    var deleteLine by remember { mutableStateOf<Txn?>(null) }
    var paying by remember { mutableStateOf(false) }
    var showMath by remember { mutableStateOf(false) }

    val review = st.status == Ledger.STATUS_REVIEW
    // Opened while it needed a look: once settled, offer the next one.
    val wasReview = remember(st.id) { review }
    val computed = st.openingPaise?.let { it + st.debitsPaise - st.creditsPaise }
    val diff = if (computed != null && st.totalDuePaise != null) computed - st.totalDuePaise else null
    val paid = payments.filter { it.cardId == st.cardId && st.dueEpochDay != null && it.dueEpochDay == st.dueEpochDay }
    val paidSum = paid.sumOf { it.amountPaise }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp)) {
        item {
            // Amount, due, status, and the one action you came for.
            Panel {
                Column {
                    Text("Total due", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(st.totalDuePaise?.let { Money.inr(it, true) } ?: "—", style = MaterialTheme.typography.headlineLarge.tabular)
                    Text(
                        listOfNotNull(
                            st.dueEpochDay?.let { "Due ${Dates.long(LocalDate.ofEpochDay(it))}" },
                            st.minDuePaise?.let { "Minimum ${Money.inr(it, true)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(st, paidSum)
                        if (diff != null || st.openingPaise == null) {
                            val ok = diff != null && abs(diff) <= 100
                            StatusPill(
                                if (ok) "✓ Adds up" else if (diff == null) "Opening balance missing" else "Off by ${Money.inr(abs(diff), true)}",
                                if (ok) Good else Amber,
                            ) { showMath = !showMath }
                        }
                    }
                    if (!review && st.note.isNotBlank() && st.note != "Accepted after review") {
                        Text(
                            st.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    AnimatedVisibility(showMath) {
                        Column(Modifier.padding(top = 10.dp)) {
                            KeyValue("Statement date", Dates.long(LocalDate.ofEpochDay(st.statementEpochDay)))
                            st.periodStartEpochDay?.let { KeyValue("Period from", Dates.long(LocalDate.ofEpochDay(it))) }
                            KeyValue("Opening balance", st.openingPaise?.let { Money.inr(it, true) } ?: "not found")
                            KeyValue("+ Spends and fees", Money.inr(st.debitsPaise, true))
                            KeyValue("− Payments and credits", Money.inr(st.creditsPaise, true))
                            computed?.let { KeyValue("= Works out to", Money.inr(it, true), if (diff != null && abs(diff) <= 100) Good else Danger) }
                            InlineAction("Edit totals", onEditTotals)
                        }
                    }
                    val canPay = !review && st.dueEpochDay != null && (if ((st.totalDuePaise ?: 0) > 0) paidSum < (st.totalDuePaise ?: 0) else paid.isEmpty())
                    if (canPay) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { paying = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Text(if (paidSum > 0) "  Add another payment" else if ((st.totalDuePaise ?: 0) <= 0) "  Mark as settled" else "  Mark as paid")
                        }
                    }
                }
            }

            if (review) {
                Spacer(Modifier.height(12.dp))
                ReviewHelp(st, lines, diff, ledger, onAddLine)
            } else if (wasReview) {
                Spacer(Modifier.height(12.dp))
                Panel(color = Good.copy(alpha = 0.12f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = Good)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("All sorted", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (nextReview == null) "No other statements need a look." else "$reviewLeft more need${if (reviewLeft == 1) "s" else ""} a look.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (nextReview != null) FilledTonalButton(onClick = onNext) { Text("Next") }
                    }
                }
            }

            // Rewards for this cycle.
            val unit = st.rewardUnit ?: "points"
            val rewards = listOfNotNull(
                st.rewardPointsEarned?.let { "+%,d $unit".format(it) },
                st.rewardPointsBalance?.let { "%,d $unit balance".format(it) },
                st.cashbackPaise?.let { "${Money.inr(it, true)} cashback" },
            )
            if (rewards.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rewards.forEach { StatusPill(it, Good) }
                }
            }
        }

        // Lines, grouped: credits, spends by category, fees.
        val credits = lines.filter { it.isCredit }
        val fees = lines.filter { !it.isCredit && it.kind == TxnKind.FEE }
        val spends = lines.filter { !it.isCredit && it.kind != TxnKind.FEE }
        if (lines.isEmpty()) {
            item { Text("No lines on this statement.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp)) }
        }
        if (spends.isNotEmpty()) {
            item { SectionHeader("Spends", trailing = Money.inr(spends.sumOf { it.amountPaise }, true)) }
            spends.groupBy { it.categoryId }.toList().sortedByDescending { (_, l) -> l.sumOf { it.amountPaise } }.forEach { (catId, l) ->
                val c = catId?.let { catById[it] }
                item(key = "c${st.id}-$catId") {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
                        Text(c?.name ?: "Uncategorised", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(Money.inr(l.sumOf { it.amountPaise }, true), style = MaterialTheme.typography.labelLarge.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(l, key = { it.id }) { t -> TxnRow(t, null, c, hPad = 4.dp) { open = t } }
            }
        }
        if (fees.isNotEmpty()) {
            item { SectionHeader("Fees and interest", trailing = Money.inr(fees.sumOf { it.amountPaise }, true), color = Danger) }
            items(fees, key = { it.id }) { t -> TxnRow(t, null, catById[t.categoryId], hPad = 4.dp) { open = t } }
        }
        if (credits.isNotEmpty()) {
            item { SectionHeader("Payments and credits", trailing = Money.inr(credits.sumOf { it.amountPaise }, true), color = Good) }
            items(credits, key = { it.id }) { t -> TxnRow(t, null, catById[t.categoryId], hPad = 4.dp) { open = t } }
        }
    }

    open?.let { t ->
        TxnSheet(
            txn = t, card = cw, categories = categories, statement = null,
            onDismiss = { open = null }, onOpenStatement = null,
            onSave = { catId, pattern, kind, note -> open = null; scope.launch { saveTxnEdits(ledger, t, catId, pattern, kind, note) } },
            extra = {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open = null; editLine = t }, modifier = Modifier.weight(1f)) { Text("Edit line") }
                    OutlinedButton(onClick = { open = null; deleteLine = t }, modifier = Modifier.weight(1f)) { Text("Delete", color = Danger) }
                }
            },
        )
    }
    editLine?.let { t ->
        LineDialog(t, onDismiss = { editLine = null }, onSave = { nt -> editLine = null; scope.launch { ledger.saveLine(nt) } }, onDelete = null)
    }
    deleteLine?.let { t ->
        ConfirmDeleteLine(t, onDismiss = { deleteLine = null }, onConfirm = { deleteLine = null; scope.launch { ledger.deleteLine(t) } })
    }
    if (paying && cw != null && st.dueEpochDay != null) {
        MarkPaidSheet(
            dueLabel = Dates.weekday(LocalDate.ofEpochDay(st.dueEpochDay)),
            total = (st.totalDuePaise ?: 0) - paidSum, minimum = st.minDuePaise,
            onDismiss = { paying = false },
            onConfirm = { amt -> paying = false; scope.launch { repo.markPaid(st.cardId, st.dueEpochDay, amt) } },
        )
    }
}

@Composable
private fun StatusChip(st: Statement, paidSum: Long) {
    val total = st.totalDuePaise ?: 0
    val due = st.dueEpochDay?.let(LocalDate::ofEpochDay)
    val days = due?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    val (text, color) = when {
        st.status == Ledger.STATUS_REVIEW -> "Needs a look" to Amber
        total <= 0 -> "Nothing due" to Good
        paidSum >= total -> "Paid" to Good
        paidSum > 0 -> "Part paid · ${Money.inr(total - paidSum, true)} left" to Amber
        days == null -> "Due date unknown" to Amber
        days < 0 -> "Overdue" to Danger
        days == 0L -> "Due today" to Danger
        days == 1L -> "Due tomorrow" to Amber
        else -> "Due in $days days" to (if (days <= 5) Amber else MaterialTheme.colorScheme.primary)
    }
    StatusPill(text, color)
}

/**
 * Why it doesn't add up, and the likely one-tap fix: a line with the wrong sign, a duplicate,
 * a missing opening balance, or a missing line.
 */
@Composable
private fun ReviewHelp(st: Statement, lines: List<Txn>, diff: Long?, ledger: Ledger, onAddLine: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val tol = 100L
    Panel(color = Amber.copy(alpha = 0.12f)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, null, tint = Amber)
                Text("Doesn't add up yet", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
            }
            when {
                st.totalDuePaise == null -> {
                    Text("The total due wasn't found. Enter it from the PDF.", style = MaterialTheme.typography.bodyMedium)
                }
                diff == null -> {
                    val opening = st.totalDuePaise - st.debitsPaise + st.creditsPaise
                    Text("The opening balance wasn't found.", style = MaterialTheme.typography.bodyMedium)
                    if (opening >= 0) {
                        FilledTonalButton(onClick = { scope.launch { ledger.editStatementHeader(st.id, st.dueEpochDay, st.totalDuePaise, st.minDuePaise, opening) } }) {
                            Text("Set opening to ${Money.inr(opening, true)}")
                        }
                    }
                }
                else -> {
                    val flip = lines.firstOrNull { abs(2 * it.amountPaise - abs(diff)) <= tol && (if (diff > 0) !it.isCredit else it.isCredit) }
                    val dupe = if (diff > 0) lines.firstOrNull { t ->
                        !t.isCredit && abs(t.amountPaise - diff) <= tol &&
                            lines.count { it.amountPaise == t.amountPaise && it.merchant == t.merchant && !it.isCredit } > 1
                    } else null
                    Text(
                        if (diff > 0) "The lines come to ${Money.inr(diff, true)} more than the bank's total."
                        else "The lines come to ${Money.inr(-diff, true)} less than the bank's total.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when {
                        flip != null -> {
                            Text(
                                "\"${flip.merchant}\" (${Money.inr(flip.amountPaise, true)}) looks like a ${if (flip.isCredit) "spend" else "credit"}.",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            FilledTonalButton(onClick = { scope.launch { ledger.saveLine(flip.copy(isCredit = !flip.isCredit)) } }) {
                                Text(if (flip.isCredit) "Make it a spend" else "Make it a credit")
                            }
                        }
                        dupe != null -> {
                            Text("\"${dupe.merchant}\" appears twice.", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            FilledTonalButton(onClick = { scope.launch { ledger.deleteLine(dupe) } }) { Text("Remove the extra one") }
                        }
                        diff < 0 -> FilledTonalButton(onClick = { onAddLine(false) }) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Add the missing spend")
                        }
                        else -> FilledTonalButton(onClick = { onAddLine(true) }) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text("  Add the missing payment or refund")
                        }
                    }
                }
            }
            InlineAction("It's fine, accept as is") { scope.launch { ledger.markReviewed(st.id) } }
        }
    }
}

/** Same confirmation as deleting a card or statement. */
@Composable
private fun ConfirmDeleteLine(t: Txn, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "Delete this line?",
        body = "\"${t.merchant.ifBlank { "Untitled" }}\" (${Money.inr(t.amountPaise, true)}) is removed from this statement. This can't be undone.",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

private fun newLine(st: Statement, credit: Boolean) = Txn(
    cardId = st.cardId, statementId = st.id, epochDay = st.statementEpochDay, merchant = "",
    amountPaise = 0, isCredit = credit, categoryId = null, dedupeKey = "",
)

@Composable
private fun LineDialog(t: Txn, onDismiss: () -> Unit, onSave: (Txn) -> Unit, onDelete: (() -> Unit)?) {
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.ofEpochDay(t.epochDay)) }
    var merchant by remember { mutableStateOf(t.merchant) }
    var amount by remember { mutableStateOf(if (t.amountPaise > 0) Money.toInput(t.amountPaise) else "") }
    var credit by remember { mutableStateOf(t.isCredit) }
    val a = Money.parse(amount)
    val valid = date != null && a != null && a > 0 && merchant.isNotBlank()
    EditSheet(
        title = if (t.id == 0L) "Add line" else "Edit line",
        onDismiss = onDismiss, confirmLabel = "Save", confirmEnabled = valid,
        onConfirm = { onSave(t.copy(epochDay = date!!.toEpochDay(), merchant = merchant.trim(), amountPaise = a!!, isCredit = credit)) },
    ) {
        DateField("Date", date, { date = it })
        OutlinedTextField(merchant, { merchant = it }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MoneyField("Amount", amount) { amount = it }
        Row(Modifier.fillMaxWidth().clickable { credit = !credit }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = credit, onCheckedChange = null)
            Text("Credit (payment, refund, cashback)", modifier = Modifier.padding(start = 12.dp))
        }
        if (onDelete != null) InlineAction("Delete this line", onDelete)
    }
}