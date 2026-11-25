package app.cardkeep.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cardkeep.capture.ChipCard
import app.cardkeep.capture.EmvReader

/** True when this phone has an NFC chip at all (the button is hidden otherwise). */
@Composable
fun nfcAvailable(): Boolean {
    val context = LocalContext.current
    return remember { NfcAdapter.getDefaultAdapter(context) != null }
}

/**
 * "Hold your card to the back of the phone": reader mode while the sheet is open, one EMV read,
 * then [onRead]. Nothing leaves the phone; the CVV isn't on the chip and is never read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapCardSheet(onRead: (ChipCard) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    var status by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }

    DisposableEffect(adapter, enabled) {
        if (adapter == null || !enabled) return@DisposableEffect onDispose { }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        adapter.enableReaderMode(activity, { tag ->
            val iso = IsoDep.get(tag)
            if (iso == null) { activity.runOnUiThread { status = "That isn't a payment card." }; return@enableReaderMode }
            activity.runOnUiThread { reading = true; status = null }
            val card = runCatching { EmvReader.read(iso) }.getOrNull()
            activity.runOnUiThread {
                reading = false
                if (card != null) onRead(card)
                else status = "Couldn't read it. Hold the card still and try again."
            }
        }, flags, null)
        onDispose { runCatching { adapter.disableReaderMode(activity) } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (reading) CircularProgressIndicator(Modifier.size(56.dp))
            else Icon(Icons.Outlined.Contactless, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                when {
                    !enabled -> "NFC is off"
                    reading -> "Reading… keep holding"
                    else -> "Hold your card to the back of the phone"
                },
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Text(
                if (!enabled) "Turn on NFC to read the card's number and expiry."
                else "Reads the number and expiry. Type the CVV yourself.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            if (!enabled) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }) { Text("Open NFC settings") }
                TextButton(onClick = { enabled = adapter?.isEnabled == true }) { Text("I've turned it on") }
            }
        }
    }
}
