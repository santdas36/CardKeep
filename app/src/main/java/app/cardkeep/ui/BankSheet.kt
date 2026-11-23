package app.cardkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.cardkeep.domain.Issuers
import kotlinx.coroutines.launch

/** Banks most cards belong to: listed first so they're one tap away. */
private val POPULAR = listOf("HDFC Bank", "SBI Card", "ICICI Bank", "Axis Bank", "Kotak")
private const val OTHER = "Other"

/**
 * Picks the issuing bank: Popular (plus the current bank), then every bank A–Z with "Other" last.
 * Searching folds both into one list. Picking applies and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankSheet(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    fun pick(name: String) {
        onSelect(name)
        scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() }
    }

    val names = Issuers.all.map { it.name }
    val known = POPULAR.filter { it in names }
    val popular = known + listOf(selected).filter { it != OTHER && it !in known && it in names }
    val az = names.filter { it != OTHER }.sortedBy { it.lowercase() } + names.filter { it == OTHER }
    val q = query.trim()
    val results = if (q.isEmpty()) null else az.filter { it.contains(q, ignoreCase = true) }
    // As tall as the list needs, never more than most of the screen.
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetTitle("Bank")
        SheetSearchField(query, { query = it }, "Search banks")
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = maxHeight),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        ) {
            if (results != null) {
                if (results.isEmpty()) item("none") {
                    Text(
                        "No bank matches \"$q\". Pick Other if it isn't listed.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    )
                }
                items(results, key = { "r_$it" }) { BankRow(it, it == selected) { pick(it) } }
            } else {
                item("h_popular") { SheetSectionLabel("Popular") }
                items(popular, key = { "p_$it" }) { BankRow(it, it == selected) { pick(it) } }
                item("h_all") { SheetSectionLabel("All banks") }
                items(az, key = { "a_$it" }) { BankRow(it, it == selected) { pick(it) } }
            }
        }
    }
}

@Composable
private fun BankRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val ink = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BankBadge(name, size = 28.dp)
        Text(name, style = MaterialTheme.typography.bodyLarge, color = ink, modifier = Modifier.weight(1f).padding(start = 14.dp))
        if (selected) Icon(Icons.Filled.Check, null, tint = ink)
    }
}

/* Shared by the edit form's sheets (bank, design) so they read as one set. */

/** Sheet title, with an optional action on the right (e.g. "Done"). */
@Composable
fun SheetTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Rounded search field with a clear button. */
@Composable
fun SheetSearchField(query: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query, onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear search") } },
        singleLine = true, shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** Small section label inside a sheet list. */
@Composable
fun SheetSectionLabel(text: String, modifier: Modifier = Modifier) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
)
