package app.cardkeep.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.cardkeep.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed interface Check {
    data object Idle : Check
    data object Checking : Check
    data object UpToDate : Check
    data class Available(val release: Updater.Release) : Check
    data class Failed(val message: String) : Check
}

/**
 * "Check for updates" row for Settings → About. Only checks when tapped; a newer release opens a sheet
 * with its notes, where it can be downloaded and installed.
 */
@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<Check>(Check.Idle) }
    var sheet by remember { mutableStateOf<Updater.Release?>(null) }

    fun run() {
        check = Check.Checking
        scope.launch {
            check = runCatching { Updater.latest() }.fold(
                onSuccess = { r -> if (Updater.isNewer(r.version, Updater.installedVersion(context))) Check.Available(r) else Check.UpToDate },
                onFailure = { Check.Failed("Couldn't reach GitHub. Check your connection and try again.") },
            )
            (check as? Check.Available)?.let { sheet = it.release }
        }
    }

    val subtitle = when (val c = check) {
        Check.Idle -> "Version ${Updater.installedVersion(context)}"
        Check.Checking -> "Checking…"
        Check.UpToDate -> "Up to date"
        is Check.Available -> "Version ${c.release.version} is available"
        is Check.Failed -> c.message
    }
    ListRow(
        Icons.Outlined.SystemUpdate, "Check for updates", subtitle,
        onClick = when (val c = check) {
            Check.Checking -> null
            is Check.Available -> ({ sheet = c.release })
            else -> ::run
        },
        action = when (check) {
            Check.Checking -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
            is Check.Available -> ({ StatusPill("Update", MaterialTheme.colorScheme.primary, onClick = { sheet = (check as Check.Available).release }) })
            else -> null
        },
    )

    sheet?.let { r -> UpdateSheet(r, onDismiss = { sheet = null }) }
}

@Composable
private fun UpdateSheet(r: Updater.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selfUpdate = remember { Updater.canSelfUpdate(context) }

    fun install(file: File) {
        val problem = Updater.verify(context, file)
        if (problem != null) {
            error = problem
            Updater.cleanUp(context)
            apk = null
            return
        }
        if (!Updater.mayInstall(context)) return // the button now asks for permission
        runCatching { Updater.install(context, file) }.onFailure { error = "Couldn't start the installer" }
    }

    // Back from "Install unknown apps": carry on if it was allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { apk?.let { if (Updater.mayInstall(context)) install(it) } }

    val downloading = job?.isActive == true
    val needsPermission = apk != null && !Updater.mayInstall(context)
    val label = when {
        !selfUpdate -> "Open release page"
        downloading -> "Downloading… ${((progress ?: 0f) * 100).toInt()}%"
        needsPermission -> "Allow installs from Cards"
        apk != null -> "Install"
        else -> "Download and install"
    }
    val size = if (r.apkSize > 0) " · %.1f MB".format(r.apkSize / 1_048_576f) else ""

    EditSheet(
        title = "Version ${r.version}",
        note = "You have ${Updater.installedVersion(context)}. Released ${r.publishedAt}$size.",
        confirmLabel = label,
        confirmEnabled = !downloading,
        onDismiss = { job?.cancel(); onDismiss() },
        onConfirm = {
            error = null
            val file = apk
            when {
                !selfUpdate -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE))) }
                needsPermission -> openSystemSettings(
                    context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
                file != null -> install(file)
                else -> job = scope.launch {
                    progress = 0f
                    runCatching { Updater.download(context, r) { progress = it } }
                        .onSuccess { apk = it; install(it) }
                        .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = "Download failed. Try again." }
                    progress = null
                }
            }
        },
    ) {
        if (r.notes.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(plainNotes(r.notes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (downloading) LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        if (needsPermission) {
            Text(
                "Android asks once before Cards can install updates.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selfUpdate) {
            Text(
                "Debug builds can't update. Get the release from GitHub.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Release notes are Markdown; show them as plain text. */
private fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
    line.trimEnd()
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^\\s*[-*]\\s+"), "• ")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
}.replace(Regex("\n{3,}"), "\n\n").trim()
