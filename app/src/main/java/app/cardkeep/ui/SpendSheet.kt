package app.cardkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.cardkeep.CardsApp
import app.cardkeep.data.Card
import app.cardkeep.data.Category
import app.cardkeep.data.Spend
import app.cardkeep.data.SpendReview
import app.cardkeep.data.SpendSource
import app.cardkeep.domain.Money
import app.cardkeep.ingest.Categorizer
import app.cardkeep.ingest.TxnKind
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** The amount as the spend log shows it: rupees, or the alert's own currency code for spends abroad. */
fun spendAmountText(s: Spend): String =
    if (s.currency == "INR") Money.inr(s.amountPaise, true) else "${s.currency} ${Money.inr(s.amountPaise, true).removePrefix("₹")}"

/** One spend from the log, or a new one by hand when [spend] is null. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpendSheet(
    spend: Spend?,
    cards: List<Card>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    defaultCardId: Long? = null,
) {
    val scope = rememberCoroutineScope()
    val log = CardsApp.from(LocalContext.current).repo.spendLog
    val foreign = spend != null && spend.currency != "INR"
    val pickable = remember(cards, spend) { cards.filter { it.archivedAt == null || it.id == spend?.cardId } }
    val start = remember { spend?.let { Instant.ofEpochMilli(it.atMillis).atZone(ZoneId.systemDefault()) } ?: ZonedDateTime.now() }

    var amount by remember { mutableStateOf(spend?.let { Money.toInput(it.amountPaise) } ?: "") }
    var merchant by remember { mutableStateOf(spend?.merchant ?: "") }
    var cardId by remember { mutableStateOf(spend?.cardId ?: pickable.firstOrNull { it.id == defaultCardId }?.id ?: pickable.firstOrNull()?.id) }
    var date by remember { mutableStateOf<LocalDate?>(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime().withSecond(0).withNano(0)) }
    var kind by remember { mutableStateOf(spend?.kind ?: TxnKind.SPEND) }
    var cat by remember { mutableStateOf(spend?.categoryId) }
    var applyAll by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(spend?.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingCard by remember { mutableStateOf(false) }

    val paise = Money.parse(amount)
    val valid = paise != null && paise > 0 && cardId != null && date != null

    EditSheet(
        title = if (spend == null) "Add spend" else "Spend",
        onDismiss = onDismiss,
        confirmLabel = if (spend == null) "Add spend" else "Save",
        confirmEnabled = valid,
        onConfirm = {
            val credit = if (spend != null && kind == spend.kind) spend.isCredit else kind != TxnKind.SPEND && kind != TxnKind.FEE
            val base = spend ?: Spend(
                cardId = cardId!!, atMillis = 0, epochDay = 0, amountPaise = 0, isCredit = false, kind = TxnKind.SPEND, currency = "INR",
                merchant = "", categoryId = null, note = "", source = SpendSource.MANUAL, availablePaise = null, reference = "",
                dedupeKey = "", review = SpendReview.NONE, statementId = null, statementTxnId = null,
            )
            val edited = base.copy(
                cardId = cardId!!, atMillis = date!!.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                amountPaise = paise!!, isCredit = credit, kind = kind, merchant = merchant.trim(), categoryId = cat, note = note.trim(),
            )
            scope.launch {
                // The rule goes in first: saving after it keeps every edit above, not just the category.
                if (applyAll && spend != null) log.setCategory(spend, cat, Categorizer.merchantKey(edited.merchant))
                log.save(edited)
                onDismiss()
            }
        },
    ) {
        if (spend != null) SpendInfo(spend) { scope.launch { log.dismiss(spend); onDismiss() } }

        if (foreign) {
            OutlinedTextField(
                amount, { amount = it }, label = { Text("Amount") }, prefix = { Text("${spend?.currency} ") }, singleLine = true,
                isError = amount.isNotBlank() && paise == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            )
        } else MoneyField("Amount", amount) { amount = it }
        OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Date", date, { date = it }, Modifier.weight(1f))
            TimeField("Time", time, { time = it }, Modifier.weight(1f))
        }

        Text("Card", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        val chosen = cards.firstOrNull { it.id == cardId }
        // Alerts are matched to a card by bank and last digits, so there's nothing to pick for them.
        val fromAlert = spend != null && spend.source != SpendSource.MANUAL
        when {
            pickable.isEmpty() && chosen == null ->
                Text("Add a card first, then log spends on it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Panel(padded = false) {
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (fromAlert) Modifier else Modifier.clickable { pickingCard = true })
                        .heightIn(min = 60.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    chosen?.let { BankBadge(it.issuer, size = 34.dp) }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(chosen?.let { "${it.nickname} •• ${it.last4}" } ?: "Choose a card", style = MaterialTheme.typography.bodyLarge)
                        if (fromAlert) Text("From the bank alert", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!fromAlert) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Change card", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text("Type", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val kinds = listOf(TxnKind.SPEND, TxnKind.REFUND, TxnKind.PAYMENT, TxnKind.CASHBACK) + listOfNotNull(TxnKind.FEE.takeIf { spend?.kind == it })
            kinds.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(TxnKind.label(k)) }) }
        }
        Text("Payments and cashback don't count as spends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Category", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { c ->
                FilterChip(
                    selected = cat == c.id, onClick = { cat = if (cat == c.id) null else c.id }, label = { Text(c.name) },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color))) },
                )
            }
        }
        if (spend != null && cat != null && merchant.isNotBlank()) {
            Row(Modifier.fillMaxWidth().clickable { applyAll = !applyAll }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = applyAll, onCheckedChange = null)
                Text("Use this category for all spends from this merchant", modifier = Modifier.padding(start = 8.dp))
            }
        }

        OutlinedTextField(
            note, { note = it.take(200) }, label = { Text("Note") },
            placeholder = { Text("e.g. split with a friend, office expense") }, modifier = Modifier.fillMaxWidth(),
        )
        if (spend != null) InlineAction("Delete this spend") { confirmDelete = true }
    }

    if (pickingCard) CardPickerSheet(pickable, cardId, onPick = { cardId = it; pickingCard = false }, onDismiss = { pickingCard = false })

    if (confirmDelete && spend != null) {
        ConfirmDialog(
            title = "Delete this spend?",
            body = "Removes \"${spend.merchant.ifBlank { TxnKind.label(spend.kind) }}\" (${spendAmountText(spend)}) from your spends. This can't be undone.",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; scope.launch { log.delete(spend); onDismiss() } },
        )
    }
}

/** Choose one card; a search box shows up once the wallet is long enough to need one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardPickerSheet(cards: List<Card>, selected: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    if (LocalLocked.current) return
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = cards.filter {
        q.isEmpty() || it.nickname.lowercase().contains(q) || it.issuer.lowercase().contains(q) || it.last4.contains(q)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Choose a card", style = MaterialTheme.typography.titleLarge)
            if (cards.size > 6) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Name, bank or last 4 digits") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            Panel(padded = false, modifier = Modifier.padding(top = 12.dp).heightIn(max = 480.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    shown.forEachIndexed { i, c ->
                        if (i > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c.id) }.heightIn(min = 60.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BankBadge(c.issuer, size = 34.dp)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text("${c.nickname} •• ${c.last4}", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    "${c.issuer} · ${app.cardkeep.data.CardKind.label(c.kind)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                            RadioButton(selected == c.id, null)
                        }
                    }
                    if (shown.isEmpty()) {
                        Text(
                            "No card matches \"$query\".", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Where the spend came from, the limit the alert reported, and how it checks against the statement. */
@Composable
private fun SpendInfo(spend: Spend, onLooksFine: () -> Unit) {
    Panel {
        KeyValue(
            "Source",
            when (spend.source) {
                SpendSource.SMS -> "From SMS"
                SpendSource.NOTIFICATION -> "From a message notification"
                else -> "Added by you"
            },
        )
        spend.availablePaise?.let { KeyValue("Available limit", Money.inr(it, true)) }
        val explanation = when (spend.review) {
            SpendReview.MISSING -> "Not on your statement"
            SpendReview.DIFFERS -> "Different amount on your statement"
            SpendReview.MATCHED -> "Matches your statement."
            SpendReview.DISMISSED -> "Kept by you"
            else -> null
        }
        explanation?.let {
            val flagged = SpendReview.flagged(spend.review)
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = if (flagged) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (flagged) FilledTonalButton(onClick = onLooksFine, modifier = Modifier.padding(top = 10.dp)) { Text("Keep as is") }
        }
    }
}

/** Read-only field that opens a clock, matching [DateField]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: LocalTime, onChange: (LocalTime) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Release) open = true }
    }
    OutlinedTextField(
        value = value.format(timeFormat), onValueChange = {}, readOnly = true, singleLine = true,
        label = { Text(label) }, trailingIcon = { Icon(Icons.Outlined.Schedule, null) },
        interactionSource = source, modifier = modifier,
    )
    if (open) {
        val state = rememberTimePickerState(value.hour, value.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false; onChange(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}
