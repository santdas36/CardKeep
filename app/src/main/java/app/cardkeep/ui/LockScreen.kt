package app.cardkeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * True while the app is locked. Sheets, dialogs and menus open in their own windows above the lock cover,
 * so they read this and stay out of composition (or close) while it's set.
 */
val LocalLocked = compositionLocalOf { false }

/**
 * Opaque cover while locked. It is its own full-screen window, created after any sheet or dialog that was
 * already open, so it sits above all of them (screens without a LocalLocked check included). It inherits
 * the activity's FLAG_SECURE. Back sends the app to the background instead of reaching the screens below.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) { LockCover(onUnlock) }
}

/**
 * Fingerprint mark, title and a pill button. The unlock prompt opens on its own once per lock, when the
 * app is in front; the button brings it back after a cancel.
 */
@Composable
private fun LockCover(onUnlock: () -> Unit) {
    var asked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!asked) { asked = true; onUnlock() } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Fingerprint, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(28.dp))
            Text("Cards is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Unlock with your fingerprint or screen lock", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth(0.6f).height(52.dp)) { Text("Unlock") }
        }
    }
}
