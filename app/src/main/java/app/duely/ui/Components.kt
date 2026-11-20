package app.duely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.duely.domain.Money

/*
 * Shared building blocks. Every screen uses these so spacing, type and behaviour match:
 *  - content sits [Gutter] from the screen edge
 *  - sections start with [SectionHeader]; grouped content sits in a [Panel]
 *  - lists inside a panel use [ListRow] separated by [RowDivider]
 *  - editing happens in an [EditSheet] (bottom sheet); only destructive confirms are dialogs ([ConfirmDialog])
 */

/** Horizontal margin for all screen content. */
val Gutter = 16.dp

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.titleSmall.tabular, color = color) }
    }
}

/** A rounded surface for a group of related content. [padded] = false for lists of [ListRow]s. */
@Composable
fun Panel(modifier: Modifier = Modifier, padded: Boolean = true, color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(if (padded) Modifier.padding(16.dp) else Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** Small label + value tile, used in grids. */
@Composable
fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** Label on the left, value on the right. */
@Composable
fun KeyValue(k: String, v: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium.tabular, color = valueColor)
    }
}

/**
 * The one list row: tinted icon circle, title, optional subtitle, then a value, an action, or a chevron.
 * Use inside a `Panel(padded = false)`.
 */
@Composable
fun ListRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    /** Custom leading content (e.g. a thumbnail) when there's no [icon]. */
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
        } else leading?.invoke()
        Column(Modifier.weight(1f).padding(start = if (icon != null || leading != null) 14.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.bodyLarge.tabular, modifier = Modifier.padding(start = 8.dp)) }
        action?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Divider between [ListRow]s, inset past the icon. */
@Composable
fun RowDivider(inset: Boolean = true) =
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = if (inset) 64.dp else 16.dp))

/** Status pill: tinted background, same-colour text. Tappable when [onClick] is set. */
@Composable
fun StatusPill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(
        color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50),
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else Modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Rupee amount input: ₹ prefix, decimal keyboard, flags what doesn't parse. */
@Composable
fun MoneyField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, prefix = { Text("₹ ") }, singleLine = true,
        isError = value.isNotBlank() && Money.parse(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

/**
 * Bottom sheet for every edit: title, optional note, fields, and one full-width primary button.
 * Swipe down or tap outside to cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
            Button(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp)) { Text(confirmLabel) }
        }
    }
}

/** Confirmation for destructive actions only. Says what goes, and whether it can be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Delete",
    destructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (LocalLocked.current) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = if (destructive) Danger else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The one "mark paid" flow, from the card or from a statement. Options in order: full amount, minimum
 * due (when known), other amount. With nothing due it offers to mark the cycle settled instead.
 */
@Composable
fun MarkPaidSheet(dueLabel: String, total: Long, minimum: Long?, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    if (total <= 0) {
        EditSheet(
            title = "No payment needed",
            note = "Nothing is due for the payment due $dueLabel. Mark it settled to stop its reminders.",
            onDismiss = onDismiss, confirmLabel = "Mark as settled", onConfirm = { onConfirm(0) },
        ) {}
        return
    }
    val hasMin = minimum != null && minimum in 1 until total
    var choice by remember { mutableStateOf("total") }
    var other by remember { mutableStateOf("") }
    val amount = when (choice) {
        "total" -> total
        "min" -> minimum
        else -> Money.parse(other)?.takeIf { it > 0 }
    }
    EditSheet(
        title = "Mark as paid", note = "For the payment due $dueLabel. Its reminders stop.",
        onDismiss = onDismiss, confirmLabel = amount?.let { "Mark ${Money.inr(it, true)} paid" } ?: "Mark as paid",
        confirmEnabled = amount != null, onConfirm = { amount?.let(onConfirm) },
    ) {
        Panel(padded = false) {
            PayOption("Full amount", Money.inr(total, true), choice == "total") { choice = "total" }
            if (hasMin) {
                RowDivider(inset = false)
                PayOption("Minimum due", Money.inr(minimum!!, true), choice == "min") { choice = "min" }
            }
            RowDivider(inset = false)
            PayOption("Other amount", null, choice == "other") { choice = "other" }
        }
        if (choice == "other") MoneyField("Amount paid", other) { other = it }
    }
}

@Composable
private fun PayOption(label: String, value: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge.tabular) }
    }
}
/** Edit one amount (e.g. the card's outstanding balance). */
@Composable
fun AmountSheet(title: String, note: String, initialPaise: Long, confirmLabel: String = "Save", onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by remember { mutableStateOf(Money.toInput(initialPaise)) }
    val parsed = Money.parse(text)
    EditSheet(title, onDismiss, confirmLabel, confirmEnabled = parsed != null, onConfirm = { parsed?.let(onConfirm) }, note = note) {
        MoneyField("Amount", text) { text = it }
    }
}

/** Tappable text link styled like the rest (used for "Edit totals", "Accept as is", …). */
@Composable
fun InlineAction(text: String, onClick: () -> Unit) =
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) { Text(text) }
