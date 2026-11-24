package app.cardkeep.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Category
import app.cardkeep.data.Ledger
import app.cardkeep.data.Statement
import app.cardkeep.data.Txn
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.ingest.Categorizer
import app.cardkeep.ingest.SyncState
import app.cardkeep.ingest.TxnKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Net spending of a list: purchases and fees, less refunds. Payments and cashback are left out. */
private fun spentOf(list: List<Txn>) = list.sumOf { TxnKind.spendSign(it.kind, it.isCredit) * it.amountPaise }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityScreen(
    txns: List<Txn>,
    cards: List<CardWithDue>,
    categories: List<Category>,
    statements: List<Statement>,
    mailConfigured: Boolean,
    lastSync: String,
    sync: SyncState,
    ledger: Ledger,
    onSync: () -> Unit,
    onSetupMail: () -> Unit,
    onOpenStatement: (Long) -> Unit,
    needsYou: Int = 0,
    onImport: () -> Unit = {},
    bottomBar: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var cardFilter by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var catFilter by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var showFilters by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Txn?>(null) }
    val ym = YearMonth.parse(month)

    val cardById = remember(cards) { cards.associateBy { it.card.id } }
    val catById = remember(categories) { categories.associateBy { it.id } }

    // A filter for a card or category that's gone (deleted) would hide everything with no chip to remove
    // it, so drop it. Skipped while the lists are still loading (empty).
    LaunchedEffect(cardById) {
        if (cardById.isNotEmpty() && cardFilter.any { it !in cardById }) cardFilter = ArrayList(cardFilter.filter { it in cardById })
    }
    LaunchedEffect(catById) {
        if (catById.isNotEmpty() && catFilter.any { it !in catById }) catFilter = ArrayList(catFilter.filter { it in catById })
    }

    // Locked: sheets and menus are windows above the lock cover, so close them.
    val locked = LocalLocked.current
    LaunchedEffect(locked) { if (locked) { showFilters = false; open = null; menu = false } }
    // Back closes the search before switching back to the Cards tab.
    androidx.activity.compose.BackHandler(!locked && LocalTabSelected.current && searching) { searching = false; query = "" }

    fun matches(t: Txn, m: YearMonth, q: String) =
        YearMonth.from(LocalDate.ofEpochDay(t.epochDay)) == m &&
            (cardFilter.isEmpty() || t.cardId in cardFilter) &&
            (catFilter.isEmpty() || t.categoryId in catFilter) &&
            (q.isEmpty() || t.merchant.lowercase().contains(q) || t.note.lowercase().contains(q))

    val q = query.trim().lowercase()
    val filtered = remember(txns, q, cardFilter, catFilter, month) { txns.filter { matches(it, ym, q) } }
    val previous = remember(txns, q, cardFilter, catFilter, month) { txns.filter { matches(it, ym.minusMonths(1), q) } }
    val review = statements.filter { it.status == Ledger.STATUS_REVIEW }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { exportCsv(context, uri, filtered, cardById, catById) }
    }

    val actions: @Composable () -> Unit = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search")
                    }
                    if (mailConfigured) {
                        if (sync.running) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = onSync) { Icon(Icons.Filled.Refresh, "Sync statements now") }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu && !locked, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Import statement PDF") }, leadingIcon = { Icon(Icons.Outlined.UploadFile, null) },
                            onClick = { menu = false; onImport() },
                        )
                        DropdownMenuItem(
                            text = { Text("Email statements") }, leadingIcon = { Icon(Icons.Outlined.Mail, null) },
                            onClick = { menu = false; onSetupMail() },
                        )
                        DropdownMenuItem(
                            text = { Text("Export this month (CSV)") }, enabled = filtered.isNotEmpty(),
                            onClick = { menu = false; exporter.launch("cards-$month.csv") },
                        )
                    }
    }
    // Inside the tab pager the shared top bar shows these; standalone, the screen draws its own.
    val hosted = hostedHeader("Activity", actions)
    Scaffold(
        topBar = {
            if (!hosted) TopAppBar(title = { Text("Activity") }, actions = { actions() })
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                item {
                    AnimatedVisibility(searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search merchants") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp),
                        )
                    }
                    if (!mailConfigured && txns.isEmpty()) {
                        Prompt(Icons.Outlined.Mail, "Bring in your statements", "Connect Gmail, or import a PDF", MaterialTheme.colorScheme.primaryContainer, onSetupMail)
                    }
                    if (needsYou > 0) {
                        Prompt(
                            Icons.Outlined.WarningAmber,
                            if (needsYou == 1) "1 statement needs you" else "$needsYou statements need you",
                            "A password or which card it's for", Amber.copy(alpha = 0.16f), onSetupMail,
                        )
                    }
                    if (review.isNotEmpty()) {
                        Prompt(
                            Icons.AutoMirrored.Outlined.ReceiptLong,
                            if (review.size == 1) "1 statement needs a look" else "${review.size} statements need a look",
                            "Totals don't add up yet", Amber.copy(alpha = 0.16f),
                        ) { onOpenStatement(review.minBy { it.statementEpochDay }.id) }
                    }

                    // Month + filters on one row.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = ym.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                        Text(
                            ym.format(DateTimeFormatter.ofPattern(if (ym.year == YearMonth.now().year) "MMMM" else "MMM yyyy", Locale.ENGLISH)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = { month = ym.plusMonths(1).toString() }, enabled = ym < YearMonth.now()) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                        }
                        Spacer(Modifier.weight(1f))
                        val active = cardFilter.size + catFilter.size
                        TextButton(onClick = { showFilters = true }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp)) {
                            BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Icon(Icons.Outlined.FilterList, null) }
                            Text("  Filter")
                        }
                    }
                    if (cardFilter.isNotEmpty() || catFilter.isNotEmpty()) {
                        FlowRow(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            cardFilter.forEach { id ->
                                cardById[id]?.let { cw ->
                                    InputChip(
                                        selected = true, onClick = { cardFilter = ArrayList(cardFilter - id) },
                                        label = { Text("${cw.card.nickname} ${cw.card.last4}") },
                                        trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                                    )
                                }
                            }
                            catFilter.forEach { id ->
                                catById[id]?.let { c ->
                                    InputChip(
                                        selected = true, onClick = { catFilter = ArrayList(catFilter - id) }, label = { Text(c.name) },
                                        trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) },
                                    )
                                }
                            }
                        }
                    }

                    // Shown even for an empty month (as ₹0) once there's any data; with none, the prompt above says what to do.
                    if (txns.isNotEmpty()) {
                        SpendSummary(filtered, previous, ym, catById) { id -> if (id !in catFilter) catFilter = ArrayList(catFilter + id) }
                    }
                }

                if (filtered.isEmpty()) {
                    item {
                        Text(
                            when {
                                txns.isEmpty() -> "No transactions yet. They arrive with your statements."
                                q.isNotEmpty() || cardFilter.isNotEmpty() || catFilter.isNotEmpty() -> "Nothing matches in ${ym.month.name.lowercase().replaceFirstChar(Char::uppercase)}."
                                else -> "No transactions in ${ym.month.name.lowercase().replaceFirstChar(Char::uppercase)}."
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        )
                    }
                }

                filtered.groupBy { it.epochDay }.forEach { (day, list) ->
                    item(key = "d$day") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
                            Text(Dates.weekday(LocalDate.ofEpochDay(day)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            val daySpent = spentOf(list)
                            if (daySpent > 0) Text(Money.inr(daySpent, true), style = MaterialTheme.typography.labelLarge.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(list, key = { it.id }) { t -> TxnRow(t, cardById[t.cardId], catById[t.categoryId]) { open = t } }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter)) { bottomBar() }
        }
    }

    if (showFilters && !locked) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { cardFilter = arrayListOf(); catFilter = arrayListOf() }) { Text("Clear all") }
                }
                Text("Cards", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cards.forEach { cw ->
                        val on = cw.card.id in cardFilter
                        FilterChip(
                            selected = on, onClick = { cardFilter = ArrayList(if (on) cardFilter - cw.card.id else cardFilter + cw.card.id) },
                            label = { Text("${cw.card.nickname} ${cw.card.last4}") },
                            leadingIcon = { BankBadge(cw.card.issuer, size = 18.dp) },
                        )
                    }
                }
                Text("Categories", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        val on = c.id in catFilter
                        FilterChip(
                            selected = on, onClick = { catFilter = ArrayList(if (on) catFilter - c.id else catFilter + c.id) },
                            label = { Text(c.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color))) },
                        )
                    }
                }
                Button(onClick = { showFilters = false }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text("Show ${filtered.size} transaction${if (filtered.size == 1) "" else "s"}")
                }
            }
        }
    }

    open?.takeIf { !locked }?.let { t ->
        TxnSheet(
            txn = t, card = cardById[t.cardId], categories = categories,
            statement = statements.firstOrNull { it.id == t.statementId },
            onDismiss = { open = null },
            onOpenStatement = { open = null; onOpenStatement(it) },
            onSave = { catId, pattern, kind, note -> open = null; scope.launch { saveTxnEdits(ledger, t, catId, pattern, kind, note) } },
        )
    }
}

@Composable
private fun Prompt(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, bg: Color, onClick: () -> Unit) {
    Panel(
        modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp).clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        padded = false, color = bg,
    ) {
        Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/**
 * The month's spends (net of refunds; payments and cashback left out), change vs last month, the top
 * categories as one stacked bar plus chips, and payments/cashback kept apart. Follows the month and
 * filters above, since that's what the list below shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpendSummary(list: List<Txn>, previous: List<Txn>, ym: YearMonth, catById: Map<Long, Category>, onCategory: (Long) -> Unit) {
    val spent = spentOf(list)
    val before = spentOf(previous)
    val byCat = list.filter { TxnKind.spendSign(it.kind, it.isCredit) != 0 }
        .groupBy { it.categoryId }
        .mapValues { (_, l) -> spentOf(l) }
        .filterValues { it > 0 }
        .toList().sortedByDescending { it.second }
    val top = byCat.take(4)
    val other = byCat.drop(4).sumOf { it.second }
    val payments = list.filter { it.kind == TxnKind.PAYMENT }.sumOf { it.amountPaise }
    val cashback = list.filter { it.kind == TxnKind.CASHBACK }.sumOf { if (it.isCredit) it.amountPaise else -it.amountPaise }
    val monthName = ym.month.name.lowercase().replaceFirstChar(Char::uppercase)
    fun colorOf(id: Long?) = id?.let { catById[it] }?.let { Color(it.color) }

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
            if (top.isNotEmpty() && spent > 0) {
                Spacer(Modifier.height(12.dp))
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
                            // Tapping a category filters the list to it, as before.
                            onClick = id?.let { { onCategory(it) } },
                        )
                    }
                }
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

private suspend fun exportCsv(
    context: Context, uri: Uri, rows: List<Txn>, cards: Map<Long, CardWithDue>, cats: Map<Long, Category>,
) {
    fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val csv = buildString {
        appendLine("date,card,merchant,category,type,debit_credit,amount_inr,note")
        rows.sortedBy { it.epochDay }.forEach { t ->
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
                    esc(t.note),
                ).joinToString(","),
            )
        }
    }
    val ok = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } }.isSuccess
    }
    Toast.makeText(context, if (ok) "Exported ${rows.size} transactions" else "Export failed", Toast.LENGTH_SHORT).show()
}
