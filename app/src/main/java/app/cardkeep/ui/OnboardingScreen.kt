package app.cardkeep.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.cardkeep.data.Prefs
import app.cardkeep.reminders.Notifications
import app.cardkeep.reminders.Reminders
import kotlinx.coroutines.launch

/** How the first-run screens were left: add a card ("scan", "tap" or null to type it), or nothing. */
sealed interface OnboardingExit {
    data class AddCard(val start: String?) : OnboardingExit
    data object Later : OnboardingExit
}

private const val PAGES = 3

/**
 * First run: what the app is, the permissions reminders need, then add a first card or restore a backup.
 * Each page is a picture (hero), a title and one line, then its controls. Dots and an arrow at the bottom.
 */
@Composable
fun OnboardingScreen(prefs: Prefs, onExit: (OnboardingExit) -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val restore = rememberRestoreBackup { onExit(OnboardingExit.Later) }
    val last = pager.currentPage == PAGES - 1
    BackHandler(pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterEnd) {
                if (!last) TextButton(onClick = { onExit(OnboardingExit.Later) }) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> RemindersPage(prefs)
                    else -> FirstCardPage(onAdd = { onExit(OnboardingExit.AddCard(it)) }, onRestore = restore)
                }
            }
            Row(
                Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageDots(pager.currentPage)
                Spacer(Modifier.weight(1f))
                if (last) {
                    TextButton(onClick = { onExit(OnboardingExit.Later) }) { Text("Later") }
                } else {
                    Surface(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** One page: hero on top, then a big title, one line of text and the page's controls. Scrolls on small screens. */
@Composable
private fun Page(hero: @Composable () -> Unit, title: String, body: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    // Clipped to the page, so a hero that spills sideways doesn't draw over the next page mid-swipe.
    Column(Modifier.fillMaxSize().clipToBounds().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { hero() }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 36.sp))
        if (body != null) {
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(22.dp))
        content()
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- Welcome ----------

/**
 * Three big plain-gradient cards, fanned and spilling past the edges (a picture, not real card art).
 * They settle into the fan once when the page first shows.
 */
@Composable
private fun CardFan() {
    val settle = remember { Animatable(0f) }
    LaunchedEffect(Unit) { settle.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    // Back to front, as in the mockup: colours, rotation, and offset (in the card's own rotated frame, like
    // CSS "rotate() translate()") as a fraction of the card width.
    val cards = listOf(
        FanCard(listOf(Color(0xFF2C5BD9), Color(0xFF7B5CFF)), -12f, -26f / 190, -14f / 190),
        FanCard(listOf(Color(0xFFF2B84B), Color(0xFFE5683F)), -3f, 4f / 190, 6f / 190),
        FanCard(listOf(Color(0xFF1A1A1A), Color(0xFF3D3D46)), 7f, 30f / 190, 26f / 190),
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // As tall as the hero allows (the tilted, offset fan stands about 1.0 card-widths tall). Placed so the
        // middle (yellow) card's left edge lines up with the title below (that edge is 0.493 card-widths left of the
        // fan's centre), so the blue card behind runs off the page on the left, on purpose.
        val w = maxHeight / 1.0f
        val shiftX = w * 0.493f - maxWidth / 2
        val shape = RoundedCornerShape(w * (14f / 190))
        cards.forEachIndexed { i, c ->
            val p = settle.value
            Box(
                Modifier.requiredWidth(w).aspectRatio(190f / 120f)
                    .graphicsLayer {
                        val a = Math.toRadians(c.rotation.toDouble())
                        val dx = c.x * w.toPx()
                        val dy = c.y * w.toPx()
                        rotationZ = lerp(0f, c.rotation, p)
                        translationX = shiftX.toPx() + lerp(0f, (dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)).toFloat(), p)
                        translationY = lerp(0f, (dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)).toFloat(), p)
                    }
                    .shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .background(Brush.linearGradient(c.colors), shape),
            ) {
                if (i == cards.lastIndex) FrontCardDetails(w)
            }
        }
    }
}

/** The front card's face: name, chip, contactless mark, number, holder and network circles, all scaled to [w]. */
@Composable
private fun BoxScope.FrontCardDetails(w: Dp) {
    val k = w / 300.dp // laid out at 300dp wide, scaled from there
    val ink = Color.White
    // Soft diagonal sheen from the top-left.
    Box(Modifier.matchParentSize().background(Brush.linearGradient(0f to ink.copy(alpha = 0.14f), 0.45f to Color.Transparent)))
    Text("Cards", color = ink.copy(alpha = 0.9f), fontSize = (14 * k).sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 24.dp * k, top = 22.dp * k))
    Box(
        Modifier.padding(start = 24.dp * k, top = 68.dp * k).size(40.dp * k, 30.dp * k).clip(RoundedCornerShape(6.dp * k))
            .background(Brush.linearGradient(listOf(Color(0xFFF3D98A), Color(0xFFB8913E)))),
    )
    Icon(
        Icons.Outlined.Contactless, null, tint = ink.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 74.dp * k, top = 71.dp * k).size(24.dp * k),
    )
    Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp * k, bottom = 22.dp * k)) {
        Text("••••  4821", color = Color(0xFFE6E6E6), fontSize = (16 * k).sp, letterSpacing = (3 * k).sp)
        Spacer(Modifier.height(8.dp * k))
        Text("ALEX MORGAN", color = ink.copy(alpha = 0.6f), fontSize = (11 * k).sp, letterSpacing = (1.5f * k).sp)
    }
    Box(Modifier.align(Alignment.BottomEnd).padding(end = 22.dp * k, bottom = 22.dp * k).size(48.dp * k, 30.dp * k)) {
        Box(Modifier.size(30.dp * k).clip(CircleShape).background(Color(0xFFEB001B)))
        Box(Modifier.padding(start = 18.dp * k).size(30.dp * k).clip(CircleShape).background(Color(0xEBF79E1B)))
    }
}

private class FanCard(val colors: List<Color>, val rotation: Float, val x: Float, val y: Float)

@Composable
private fun WelcomePage() {
    Page(hero = { CardFan() }, title = "All your cards.\nNone of the worry.") {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Benefit(Icons.Outlined.NotificationsNone, "Never miss a due date", "A nudge before every bill")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            Benefit(Icons.Outlined.MailOutline, "Statements read for you", "Dues and spends from your inbox")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            Benefit(Icons.Outlined.Fingerprint, "Pay without the plastic", "Autofill at checkout")
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Encrypted · Offline · No account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One benefit row inside the grouped card: icon tile, a bold line and a short one under it. */
@Composable
private fun Benefit(icon: ImageVector, title: String, caption: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Reminders ----------

/** What a reminder looks like: one notification, and a quieter one behind it. */
@Composable
private fun ReminderPreview() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(0.94f).shadow(12.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CreditCard, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Regalia Gold due in 2 days", style = MaterialTheme.typography.bodyMedium)
                Text("₹18,420 · HDFC Bank ••4821", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("9:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Atlas due tomorrow · ₹32,960",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.86f).alpha(0.7f).clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun RemindersPage(prefs: Prefs) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val notif = remember(tick) { Notifications.enabled(context) }
    val exact = remember(tick) { Reminders.canExact(context) }
    val battery = remember(tick) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Page(hero = { ReminderPreview() }, title = "Never miss a due date", body = "A reminder at 9 am, a few days before each bill.") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionRow(Icons.Outlined.NotificationsNone, "Notifications", notif) {
                val canAsk = Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (canAsk) {
                    prefs.askedNotifications = true
                    askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Asked before (or older Android): only the system screen can turn them on now.
                    openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            PermissionRow(Icons.Outlined.Alarm, "Exact alarms", exact) {
                if (Build.VERSION.SDK_INT >= 31) {
                    openSystemSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }
            PermissionRow(Icons.Outlined.BatteryChargingFull, "Run in background", battery) { requestBatteryExemption(context) }
        }
    }
}

/** A rounded row: icon, name, then an Allow pill, or a quiet "Allowed" once it is. */
@Composable
private fun PermissionRow(icon: ImageVector, title: String, allowed: Boolean, onAllow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 14.dp))
        if (allowed) {
            Text(
                "Allowed", style = MaterialTheme.typography.labelLarge, color = Good,
                modifier = Modifier.clip(CircleShape).background(Good.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        } else {
            Button(onClick = onAllow, contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.height(34.dp)) { Text("Allow") }
        }
    }
}

// ---------- First card ----------

/** An empty card slot (dashed outline and a plus), waiting for the first card. Also on the empty Home. */
@Composable
internal fun EmptyCardSlot(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier.aspectRatio(CARD_RATIO).drawBehind {
            val stroke = 2.dp.toPx()
            drawRoundRect(
                line, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(30.dp)) }
}

@Composable
private fun FirstCardPage(onAdd: (String?) -> Unit, onRestore: () -> Unit) {
    Page(hero = { EmptyCardSlot(Modifier.width(200.dp)) }, title = "Add your first card", body = "Bank, network and type fill in on their own.") {
        AddCardTiles(onAdd)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRestore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Restore from a backup")
        }
    }
}

/** Scan / Tap / Type, side by side. [onAdd] gets "scan", "tap" or null (type it in). Tap only with NFC. */
@Composable
internal fun AddCardTiles(onAdd: (String?) -> Unit) {
    val nfc = nfcAvailable()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AddTile(Icons.Outlined.DocumentScanner, "Scan", "Camera", Modifier.weight(1f)) { onAdd("scan") }
        if (nfc) AddTile(Icons.Outlined.Nfc, "Tap", "NFC", Modifier.weight(1f)) { onAdd("tap") }
        AddTile(Icons.Outlined.Keyboard, "Type", "By hand", Modifier.weight(1f)) { onAdd(null) }
    }
}

@Composable
private fun AddTile(icon: ImageVector, title: String, caption: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PageDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGES) { i ->
            val on = i == current
            val w by animateDpAsState(if (on) 22.dp else 6.dp, label = "dot")
            Box(
                Modifier.height(6.dp).width(w).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
