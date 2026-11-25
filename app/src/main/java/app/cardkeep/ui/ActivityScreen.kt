package app.cardkeep.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Category
import app.cardkeep.data.Ledger
import app.cardkeep.data.Spend
import app.cardkeep.data.SpendReview
import app.cardkeep.data.Statement
import app.cardkeep.data.Txn
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import app.cardkeep.ingest.SyncState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActivityScreen(
    spends: List<Spend>,
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
    onOpenTracking: () -> Unit,
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
    var open by remember { mutableStateOf<Spend?>(null) }
    var openLine by remember { mutableStateOf<Txn?>(null) }
    var adding by remember { mutableStateOf(false) }
    var flaggedOnly by rememberSaveable { mutableStateOf(false) }
    val ym = YearMonth.parse(month)
    // Flagged spends in the month on screen (and the card filter), so the count matches what tapping shows.
    val flaggedCount = remember(spends, month, cardFilter) {
        spends.count {
            SpendReview.flagged(it.review) && YearMonth.from(LocalDate.ofEpochDay(it.epochDay)) == ym &&
                (cardFilter.isEmpty() || it.cardId in cardFilter)
        }
    }
    val anyFlagged = flaggedCount > 0

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

    // Once nothing is flagged any more the chip is gone, so the filter has to go with it.
    LaunchedEffect(anyFlagged) { if (!anyFlagged) flaggedOnly = false }

    // Locked: sheets and menus are windows above the lock cover, so close them.
    val locked = LocalLocked.current
    LaunchedEffect(locked) { if (locked) { showFilters = false; open = null; openLine = null; adding = false; menu = false } }
    // Back closes the search before switching back to the Cards tab.
    androidx.activity.compose.BackHandler(!locked && LocalTabSelected.current && searching) { searching = false; query = "" }

    fun matches(s: Spend, m: YearMonth, q: String) =
        YearMonth.from(LocalDate.ofEpochDay(s.epochDay)) == m &&
            (cardFilter.isEmpty() || s.cardId in cardFilter) &&
            (catFilter.isEmpty() || s.categoryId in catFilter) &&
            (q.isEmpty() || s.merchant.lowercase().contains(q) || s.note.lowercase().contains(q))

    val q = query.trim().lowercase()
    val entries = remember(spends, txns, statements) { activityEntries(spends, txns, statements) }
    val txnById = remember(txns) { txns.associateBy { it.id } }
    val filtered = remember(entries, spends, q, cardFilter, catFilter, flaggedOnly, month) {
        // "Needs a look" lists the flagged spends themselves; otherwise the statement has the final word.
        (if (flaggedOnly) spends.filter { SpendReview.flagged(it.review) } else entries)
            .filter { matches(it, ym, q) }.sortedByDescending { it.atMillis }
    }
    val previous = remember(entries, q, cardFilter, catFilter, month) { entries.filter { matches(it, ym.minusMonths(1), q) } }
    val review = statements.filter { it.status == Ledger.STATUS_REVIEW }
    var reviewing by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { exportCsv(context, uri, filtered, cardById, catById) }
    }

    val actions: @Composable () -> Unit = {
                    IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add a spend") }
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
                    if (needsYou > 0) {
                        Prompt(
                            Icons.Outlined.WarningAmber,
                            if (needsYou == 1) "1 statement needs you" else "$needsYou statements need you",
                            "Missing a password or a card", Amber.copy(alpha = 0.16f), onSetupMail,
                        )
                    }
                    if (review.isNotEmpty()) {
                        Prompt(
                            Icons.AutoMirrored.Outlined.ReceiptLong,
                            if (review.size == 1) "1 statement needs a look" else "${review.size} statements need a look",
                            // One: what's wrong with it, and tapping opens it. Several: a sheet lists each with its reason.
                            review.singleOrNull()?.note?.ifBlank { null }, Amber.copy(alpha = 0.16f),
                        ) { if (review.size == 1) onOpenStatement(review[0].id) else reviewing = true }
                    }

                    if (entries.isNotEmpty() || spends.isNotEmpty()) {
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
                            // Spends a statement disagrees with: tap to list only those.
                            if (flaggedCount > 0) {
                                FilterChip(
                                    selected = flaggedOnly, onClick = { flaggedOnly = !flaggedOnly },
                                    label = { Text("$flaggedCount", style = MaterialTheme.typography.labelLarge.tabular) },
                                    leadingIcon = { Icon(Icons.Outlined.WarningAmber, "Needs a look", Modifier.size(18.dp), tint = Amber) },
                                )
                            }
                            val active = cardFilter.size + catFilter.size
                            TextButton(onClick = { showFilters = true }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp)) {
                                BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Icon(Icons.Outlined.FilterList, null) }
                                Text("  Filter")
                            }
                        }
                        if (flaggedOnly) {
                            Text(
                                "Not matching your statement",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
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

                        SpendSummary(filtered, previous, ym, catById) { id -> if (id !in catFilter) catFilter = ArrayList(catFilter + id) }
                    }
                }

                if (filtered.isEmpty()) {
                    item {
                        if (entries.isEmpty() && spends.isEmpty()) NoSpends(onOpenTracking) { adding = true }
                        else Text(
                            if (q.isNotEmpty() || cardFilter.isNotEmpty() || catFilter.isNotEmpty() || flaggedOnly) "Nothing matches in ${ym.month.name.lowercase().replaceFirstChar(Char::uppercase)}."
                            else "No spends in ${ym.month.name.lowercase().replaceFirstChar(Char::uppercase)}.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        )
                    }
                }

                filtered.groupBy { it.epochDay }.forEach { (day, list) ->
                    item(key = "d$day") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp)) {
                            Text(Dates.weekday(LocalDate.ofEpochDay(day)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            val daySpent = netSpent(list)
                            if (daySpent > 0) Text(Money.inr(daySpent, true), style = MaterialTheme.typography.labelLarge.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(list, key = { it.id }) { s ->
                        SpendRow(s, cardById[s.cardId], catById[s.categoryId]) {
                            if (s.source == STATEMENT_SOURCE) openLine = txnById[-s.id] else open = s
                        }
                    }
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
                    Text("Show ${filtered.size} spend${if (filtered.size == 1) "" else "s"}")
                }
            }
        }
    }

    if (reviewing && review.isNotEmpty()) {
        ReviewSheet(review, cardById, onOpen = onOpenStatement, onDismiss = { reviewing = false })
    }

    openLine?.let { t ->
        TxnSheet(
            txn = t, card = cardById[t.cardId], categories = categories, statement = statements.firstOrNull { it.id == t.statementId },
            onDismiss = { openLine = null }, onOpenStatement = { openLine = null; onOpenStatement(it) },
            onSave = { catId, pattern, kind, note -> openLine = null; scope.launch { saveTxnEdits(ledger, t, catId, pattern, kind, note) } },
        )
    }

    if (!locked && (adding || open != null)) {
        SpendSheet(
            spend = open, cards = cards.map { it.card }, categories = categories,
            onDismiss = { open = null; adding = false },
            defaultCardId = cardFilter.singleOrNull(),
        )
    }
}

/** Nothing logged yet: what the log is for, and the two ways to start filling it. */
@Composable
private fun NoSpends(onOpenTracking: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Keep track of what you spend", style = MaterialTheme.typography.titleMedium)
        Text(
            "Log card spends from your bank's alerts, or add them yourself.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenTracking, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp)) { Text("Turn on spend tracking") }
        TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a spend") }
    }
}

@Composable
private fun Prompt(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, bg: Color, onClick: () -> Unit) {
    Panel(
        modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp).clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        padded = false, color = bg,
    ) {
        Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}
