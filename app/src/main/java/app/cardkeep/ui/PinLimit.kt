package app.cardkeep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.cardkeep.data.Card

/** How many cards can be pinned to the home stack (the stack shows three). */
const val MAX_PINS = 3

/**
 * Shown when pinning a fourth card: pick which pinned card makes room. The chosen one is unpinned
 * and [card] takes its place.
 */
@Composable
fun ReplacePinDialog(card: Card, pinned: List<Card>, onReplace: (Long) -> Unit, onDismiss: () -> Unit) {
    // A dialog is its own window above the lock cover: keep it out while locked.
    if (LocalLocked.current) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace a pinned card") },
        text = {
            Column {
                Text("Up to $MAX_PINS cards can be pinned to the stack. Pick one to unpin for ${card.nickname}.")
                pinned.forEach { p ->
                    ListRow(Icons.Outlined.PushPin, p.nickname, "${p.issuer} · •• ${p.last4}", onClick = { onReplace(p.id) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
