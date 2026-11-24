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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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
 * Same parts as everywhere else (Panel + ListRow); one button at the bottom; swipe or Continue between pages.
 */
@Composable
fun OnboardingScreen(prefs: Prefs, onExit: (OnboardingExit) -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val restore = rememberRestoreBackup { onExit(OnboardingExit.Later) }
    BackHandler(pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> RemindersPage(prefs)
                    else -> FirstCardPage(onAdd = { onExit(OnboardingExit.AddCard(it)) }, onRestore = restore)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 8.dp, bottom = 16.dp)) {
                PageDots(pager.currentPage, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(20.dp))
                val last = pager.currentPage == PAGES - 1
                val modifier = Modifier.fillMaxWidth().height(52.dp)
                if (last) {
                    FilledTonalButton(onClick = { onExit(OnboardingExit.Later) }, modifier) { Text("Not now") }
                } else {
                    Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier) { Text("Continue") }
                }
            }
        }
    }
}

/** One page: a leading visual, a title, a line of text, then the page's rows. Scrolls on small screens. */
@Composable
private fun Page(lead: @Composable () -> Unit, title: String, body: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(top = 48.dp, bottom = 16.dp)) {
        lead()
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        content()
    }
}

/** The page's icon in the same tinted circle as list rows, only bigger. */
@Composable
private fun LeadIcon(icon: ImageVector) = Box(
    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
    contentAlignment = Alignment.Center,
) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }

@Composable
private fun WelcomePage() {
    val context = LocalContext.current
    val icon = remember { context.packageManager.getApplicationIcon(context.applicationInfo).toBitmap(256, 256).asImageBitmap() }
    Page(
        lead = { Image(icon, null, Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))) },
        title = "Cards",
        body = "Your cards and due dates, kept on this phone.",
    ) {
        Panel(padded = false) {
            ListRow(Icons.Outlined.Lock, "Encrypted", "Card details open only with your fingerprint or PIN.")
            RowDivider()
            ListRow(Icons.Outlined.PhoneAndroid, "Stays on this phone", "No account, no cloud, no analytics.")
            RowDivider()
            ListRow(Icons.Outlined.NotificationsNone, "Reminders", "A nudge a few days before each bill is due.")
        }
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

    Page(
        lead = { LeadIcon(Icons.Outlined.NotificationsNone) },
        title = "Reminders",
        body = "Cards reminds you at 9 am a few days before each due date. Allow these so reminders arrive on time.",
    ) {
        Panel(padded = false) {
            PermissionRow(Icons.Outlined.NotificationsNone, "Notifications", "To show the reminder", notif) {
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
            RowDivider()
            PermissionRow(Icons.Outlined.Alarm, "Exact alarms", "So reminders aren't late", exact) {
                if (Build.VERSION.SDK_INT >= 31) {
                    openSystemSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }
            RowDivider()
            PermissionRow(Icons.Outlined.BatteryChargingFull, "Battery", "Set Cards to unrestricted; some phones drop alarms", battery) {
                openSystemSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}

/** A check once allowed; until then the row (and its Allow button) opens the way to allow it. */
@Composable
private fun PermissionRow(icon: ImageVector, title: String, subtitle: String, allowed: Boolean, onAllow: () -> Unit) {
    ListRow(
        icon, title, subtitle,
        onClick = if (allowed) null else onAllow,
        action = {
            if (allowed) Icon(Icons.Filled.CheckCircle, "Allowed", tint = Good)
            else TextButton(onClick = onAllow) { Text("Allow") }
        },
    )
}

@Composable
private fun FirstCardPage(onAdd: (String?) -> Unit, onRestore: () -> Unit) {
    val nfc = nfcAvailable()
    Page(
        lead = { LeadIcon(Icons.Outlined.DocumentScanner) },
        title = "Add your first card",
        body = "The bank, network and card type are filled in for you.",
    ) {
        Panel(padded = false) {
            ListRow(Icons.Outlined.DocumentScanner, "Scan", "Point the camera at the card", chevron = true, onClick = { onAdd("scan") })
            if (nfc) {
                RowDivider()
                ListRow(Icons.Outlined.Nfc, "Tap", "Hold the card to the back of the phone", chevron = true, onClick = { onAdd("tap") })
            }
            RowDivider()
            ListRow(Icons.Outlined.Keyboard, "Type it in", "Enter the details yourself", chevron = true, onClick = { onAdd(null) })
        }
        Spacer(Modifier.height(12.dp))
        Panel(padded = false) {
            ListRow(
                Icons.Outlined.CloudDownload, "Restore a backup", "From a Cards backup file",
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant, chevron = true, onClick = onRestore,
            )
        }
    }
}

@Composable
private fun PageDots(current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGES) { i ->
            val on = i == current
            val w by animateDpAsState(if (on) 20.dp else 6.dp, label = "dot")
            Box(
                Modifier.height(6.dp).width(w).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
