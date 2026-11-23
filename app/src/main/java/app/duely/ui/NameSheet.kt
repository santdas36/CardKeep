package app.duely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import app.duely.data.HolderName
import kotlinx.coroutines.launch

/**
 * Saved cardholder names: pick one to fill the field, remove ones you don't use (they're only suggestions,
 * so no confirm), or type a new one and add it. Picking or adding closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameSheet(
    names: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    fun close() { scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() } }

    val typed = HolderName.normalise(query)
    val selected = HolderName.normalise(current)
    val shown = if (typed.isEmpty()) names else names.filter { it.contains(typed) }
    val canAdd = typed.isNotEmpty() && typed !in names
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetTitle("Name on card")
        SheetSearchField(query, { query = it }, "Search or add a name")
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = maxHeight),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        ) {
            if (canAdd) item("add") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = "Add name") { onAdd(typed); close() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Add \"$typed\"", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }
            }
            if (names.isEmpty() && !canAdd) item("empty") {
                Text(
                    "Names you use on cards show up here.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
            if (shown.isNotEmpty()) {
                item("h_saved") { SheetSectionLabel("Saved names") }
                items(shown, key = { it }) { n ->
                    NameRow(n, n == selected, onPick = { onPick(n); close() }, onRemove = { onRemove(n) })
                }
            }
        }
    }
}

@Composable
private fun NameRow(name: String, selected: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val ink = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onPick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = ink, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = ink)
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, "Remove $name", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
