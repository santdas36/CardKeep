package app.duely.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.duely.capture.CardText
import app.duely.capture.ScannedCard
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Full-screen camera that reads the card number and expiry with on-device text recognition.
 * Frames are analysed in memory and dropped; nothing is saved or sent. A number is accepted only after
 * two frames agree and it passes the Luhn check.
 */
@Composable
fun ScanCardScreen(onResult: (ScannedCard) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    // Denied with "don't ask again" (or denied twice): the system won't show the prompt any more, so
    // the button has to send you to this app's settings page instead.
    var blocked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        val activity = context as? android.app.Activity
        blocked = !ok && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    // Back from the settings page: pick up a newly granted permission.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!granted && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) granted = true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) CameraReader(onResult)
            else if (asked) Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Camera access is off", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (blocked) "Turn on Camera in this app's permissions to scan a card, or type the details instead."
                    else "Allow the camera to scan a card, or type the details instead.",
                    color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                )
                TextButton(onClick = {
                    if (blocked) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        runCatching { context.startActivity(intent) }
                    } else {
                        permission.launch(Manifest.permission.CAMERA)
                    }
                }) { Text(if (blocked) "Open settings" else "Allow camera") }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraReader(onResult: (ScannedCard) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var hint by remember { mutableStateOf("Fit the card inside the frame") }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val main = ContextCompat.getMainExecutor(context)
        var lastNumber: String? = null
        var expiry: String? = null
        var issuer: String? = null // bank name read off the card, if any frame showed it
        var done = false
        var providerRef: ProcessCameraProvider? = null
        var disposed = false // closed before the camera provider was ready: don't bind

        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null || done) { proxy.close(); return@setAnalyzer }
            recognizer.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener(main) { text ->
                    if (done) return@addOnSuccessListener
                    val seen = CardText.parse(text.text)
                    if (seen.expiry != null) expiry = seen.expiry
                    if (seen.issuer != null) issuer = seen.issuer
                    val n = seen.number
                    when {
                        n != null && n == lastNumber -> { done = true; onResult(ScannedCard(n, expiry, issuer)) }
                        n != null -> { lastNumber = n; hint = "Hold still…" }
                        else -> hint = "Fit the card inside the frame"
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            val provider = runCatching { future.get() }.getOrElse { hint = "Couldn't open the camera"; return@addListener }
            providerRef = provider
            val p = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis)
            }.onFailure { hint = "Couldn't open the camera" }
        }, main)

        onDispose {
            disposed = true
            done = true
            runCatching { providerRef?.unbindAll() }
            analysis.clearAnalyzer()
            recognizer.close()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Card-shaped guide (ISO ID-1 is 85.6 × 54 mm).
            Box(Modifier.fillMaxWidth().aspectRatio(85.6f / 54f).border(2.dp, Color.White, RoundedCornerShape(14.dp)))
            Text(hint, color = Color.White, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text(
                "Use the side with the card number. Nothing is saved or sent.",
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
        }
    }
}
