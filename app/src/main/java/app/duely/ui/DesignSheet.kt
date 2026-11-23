package app.duely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.duely.domain.Issuers
import kotlinx.coroutines.launch

/**
 * Picks a card's art: Auto (matched from bank and nickname) or bank colours, this bank's designs, then
 * every other bank's (for co-branded cards filed under another bank). [selected] follows [Card.art]:
 * null = auto, [BANK_COLOURS], or an art id. Picking applies at once; Done or a swipe just closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignSheet(issuer: String, nickname: String, selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    // A sheet is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var othersOpen by rememberSaveable { mutableStateOf(false) }
    fun done() { scope.launch { state.hide() }.invokeOnCompletion { if (!state.isVisible) onDismiss() } }

    val bankColor = Issuers.byName(issuer).color
    val matched = CardArts.match(issuer, nickname)
    val q = query.trim().lowercase()
    fun hit(text: String) = q.isEmpty() || text.lowercase().contains(q)
    val own = CardArts.forBank(issuer).filter { hit(it.name) || hit(it.bank) }
    val others = CardArts.all.filter { it.bank != issuer && (hit(it.name) || hit(it.bank)) }
    val suggested = hit("auto") || hit("bank colours") || hit(issuer) || (matched != null && hit(matched.name))
    // While searching, the search is the filter: show other banks' matches without the extra tap.
    val showOthers = othersOpen || q.isNotEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        SheetTitle("Card design", "Done", ::done)
        SheetSearchField(query, { query = it }, "Search designs or banks")
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.imePadding(),
        ) {
            if (suggested) {
                header("Suggested")
                item("auto") {
                    DesignTile("Auto", matched?.name ?: "Bank colours", selected == null, matched, bankColor) { onSelect(null) }
                }
                item("bank") {
                    DesignTile("Bank colours", issuer, selected == BANK_COLOURS, null, bankColor) { onSelect(BANK_COLOURS) }
                }
            }
            if (own.isNotEmpty()) {
                header(issuer)
                items(own, key = { it.id }) { a -> DesignTile(a.name, null, selected == a.id, a, bankColor) { onSelect(a.id) } }
            }
            if (others.isNotEmpty()) {
                item("others", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = q.isEmpty(), role = Role.Button, onClickLabel = if (showOthers) "Hide" else "Show") { othersOpen = !othersOpen }
                            .semantics { stateDescription = if (showOthers) "Expanded" else "Collapsed" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetSectionLabel("Other banks", Modifier.weight(1f))
                        Text("${others.size}", style = MaterialTheme.typography.labelLarge.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (q.isEmpty()) Icon(
                            if (showOthers) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                if (showOthers) items(others, key = { it.id }) { a ->
                    DesignTile(a.name, a.bank, selected == a.id, a, Issuers.byName(a.bank).color) { onSelect(a.id) }
                }
            }
            if (!suggested && own.isEmpty() && others.isEmpty()) item("none", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "No designs match \"${query.trim()}\".", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            item("note", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Auto picks the design from the bank and nickname. Designs are approximations, without logos.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun LazyGridScope.header(text: String) =
    item("h_$text", span = { GridItemSpan(maxLineSpan) }) { SheetSectionLabel(text) }

/** The art itself, or the bank-colour gradient when [art] is null. Fills its (clipped) parent. */
@Composable
fun DesignSwatch(art: CardArt?, bankColor: Long, modifier: Modifier = Modifier) {
    if (art != null) ArtSurface(art, modifier, blur = false) else Box(modifier.cardBackground(bankColor, 0L))
}

/** A large card-shaped option: name bottom-left on a soft scrim, outline and check when selected. */
@Composable
private fun DesignTile(title: String, subtitle: String?, selected: Boolean, art: CardArt?, bankColor: Long, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val ink = if (art != null) (if (art.light) Color(0xFF15171C) else Color.White) else cardInk(bankColor)
    val scrim = if (ink == Color.White) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.55f)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).clip(shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape,
            ),
    ) {
        DesignSwatch(art, bankColor, Modifier.matchParentSize())
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, scrim)))
                .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 8.dp),
        ) {
            Text(title, color = ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
    }
}
