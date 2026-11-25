package app.cardkeep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Prefs

/** Light/dark mode and colour palette. Changes apply immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSection(prefs: Prefs) {
    val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        modes.forEachIndexed { i, (id, label) ->
            SegmentedButton(
                selected = Appearance.mode == id,
                onClick = { Appearance.mode = id; prefs.themeMode = id },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) { Text(label) }
        }
    }

    Text(
        "Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    val solids = Palettes.all.filter { !it.dynamic }
    val hasDynamic = Palettes.all.any { it.dynamic }
    val dynamicOn = Palettes.byId(Appearance.paletteId).dynamic
    // Solid colours or the wallpaper's (Android 12+). Going back to solid restores the last one picked.
    if (hasDynamic) {
        val kinds = listOf(false to "Solid", true to "Dynamic")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, (dyn, label) ->
                SegmentedButton(
                    selected = dynamicOn == dyn,
                    onClick = {
                        val id = if (dyn) Palettes.all.first { it.dynamic }.id else prefs.solidPalette
                        Appearance.paletteId = id; prefs.palette = id
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                ) { Text(label) }
            }
        }
    }
    if (dynamicOn) {
        Text(
            "Colours follow your wallpaper.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
    } else {
        // Four across: eight colours make an even 4×2 grid.
        Column(Modifier.padding(top = if (hasDynamic) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(SwatchGap)) {
            solids.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SwatchGap)) {
                    row.forEach { p -> Swatch(p, prefs, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val SwatchGap = 8.dp

@Composable
private fun Swatch(p: Palette, prefs: Prefs, modifier: Modifier) {
    val selected = Appearance.paletteId == p.id
    Column(
        modifier.clip(MaterialTheme.shapes.small)
            .selectable(selected, role = Role.RadioButton) { Appearance.paletteId = p.id; prefs.palette = p.id; prefs.solidPalette = p.id }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(p.swatch)
                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (p.swatch.luminance() > 0.5f) Color.Black else Color.White
            if (selected) Icon(Icons.Filled.Check, null, tint = ink)
        }
        Text(
            p.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
