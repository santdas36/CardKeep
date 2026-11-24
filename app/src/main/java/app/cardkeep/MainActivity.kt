package app.cardkeep

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.cardkeep.ingest.StatementSync
import app.cardkeep.reminders.Notifications
import app.cardkeep.ui.ActivityScreen
import app.cardkeep.ui.Appearance
import app.cardkeep.ui.ArchivedScreen
import app.cardkeep.ui.MailScreen
import app.cardkeep.ui.PasswordsScreen
import app.cardkeep.ui.PillNavBar
import app.cardkeep.ui.StatementScreen
import app.cardkeep.ui.Tab
import app.cardkeep.ui.TabHeaders
import app.cardkeep.ui.SharedTopBar
import app.cardkeep.ui.LocalTabHeaders
import app.cardkeep.ui.LocalTabPage
import app.cardkeep.ui.LocalTabSelected
import app.cardkeep.ui.LocalLocked
import app.cardkeep.ui.LocalNavAnimatedScope
import app.cardkeep.ui.LocalSharedTransitionScope
import app.cardkeep.security.Auth
import app.cardkeep.ui.DetailScreen
import app.cardkeep.ui.CardsTheme
import app.cardkeep.ui.EditScreen
import app.cardkeep.ui.FIXABLE
import app.cardkeep.ui.importUri
import app.cardkeep.ui.rememberPdfImporter
import app.cardkeep.ui.HomeScreen
import app.cardkeep.ui.LockScreen
import app.cardkeep.ui.OnboardingExit
import app.cardkeep.ui.OnboardingScreen
import app.cardkeep.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(true)
    private var deviceSecure by mutableStateOf(true)
    private var pendingCard by mutableStateOf<Long?>(null)
    private var pendingStatement by mutableStateOf<Long?>(null)
    private var pendingImport by mutableStateOf<android.net.Uri?>(null)
    private var fixKey by mutableStateOf<String?>(null)
    private var pendingAdd by mutableStateOf<String?>(null) // from a shortcut: SHORTCUT_ADD, "scan" or "tap"
    private var backgroundedAt = 0L

    companion object {
        const val ACTION_ADD_CARD = "app.cardkeep.action.ADD_CARD"
        const val ACTION_SCAN_CARD = "app.cardkeep.action.SCAN_CARD"
        const val ACTION_TAP_CARD = "app.cardkeep.action.TAP_CARD"
        private const val SHORTCUT_ADD = "add"
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // No screenshots, no preview in recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val app = CardsApp.from(this)
        Appearance.paletteId = app.prefs.palette
        Appearance.mode = app.prefs.themeMode
        deviceSecure = Auth.deviceSecure(this)
        locked = deviceSecure
        // Recreated (rotation, process death): the launch intent was already handled, don't open Add card again.
        handleIntent(intent, shortcuts = savedInstanceState == null)

        setContent {
            CardsTheme {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val cards by app.repo.cards.collectAsStateWithLifecycle(initialValue = null)
                val everyCard by app.repo.everyCard.collectAsStateWithLifecycle(initialValue = emptyList())
                val archived by app.repo.archived.collectAsStateWithLifecycle(initialValue = emptyList())
                val payments by app.repo.payments.collectAsStateWithLifecycle(initialValue = emptyList())

                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(locked, cards?.size) {
                    if (!locked && Build.VERSION.SDK_INT >= 33 && !cards.isNullOrEmpty() && !app.prefs.askedNotifications &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        app.prefs.askedNotifications = true
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(locked, pendingCard) {
                    val id = pendingCard
                    if (!locked && id != null) {
                        pendingCard = null
                        nav.navigate("card/$id") { launchSingleTop = true }
                    }
                }

                val ledger = app.repo.ledger
                val statements by ledger.statements.collectAsStateWithLifecycle(initialValue = emptyList())
                val txns by ledger.txns.collectAsStateWithLifecycle(initialValue = emptyList())
                val categories by ledger.categories.collectAsStateWithLifecycle(initialValue = emptyList())
                val mail by ledger.mailSource.collectAsStateWithLifecycle(initialValue = null)
                val importLog by ledger.importLog.collectAsStateWithLifecycle(initialValue = emptyList())
                val sync by StatementSync.state.collectAsStateWithLifecycle()
                val runSync: () -> Unit = { app.scope.launch { StatementSync.run(app) } }
                val pendingKeys by ledger.pendingKeys.collectAsStateWithLifecycle(initialValue = emptyList())
                val needsYou = importLog.filter { it.outcome in FIXABLE && it.key in pendingKeys }

                // After a manual or shared import: open the statement, or the fix for what's missing.
                val onImported: (StatementSync.Outcome) -> Unit = { o ->
                    android.widget.Toast.makeText(this@MainActivity, o.message, android.widget.Toast.LENGTH_LONG).show()
                    when {
                        o.statementId != null -> nav.navigate("statement/${o.statementId}")
                        o.code in FIXABLE -> { fixKey = o.key; nav.navigate("mail") { launchSingleTop = true } }
                    }
                }
                val importPdf = rememberPdfImporter(onImported)
                LaunchedEffect(locked, pendingImport) {
                    val uri = pendingImport
                    if (!locked && uri != null) {
                        pendingImport = null
                        onImported(importUri(this@MainActivity, uri))
                    }
                }

                // Cards and Activity are two pages side by side: swipe between them or tap the tab bar.
                val pager = androidx.compose.foundation.pager.rememberPagerState { Tab.entries.size }
                fun switchTab(t: Tab) { scope.launch { pager.animateScrollToPage(t.ordinal) } }

                // App shortcuts: open Add card right away, behind the lock if it's up (EditScreen waits for
                // unlock before it starts the camera or NFC).
                LaunchedEffect(pendingAdd) {
                    val start = pendingAdd ?: return@LaunchedEffect
                    pendingAdd = null
                    nav.navigate(if (start == SHORTCUT_ADD) "edit/0" else "edit/0?start=$start")
                }

                LaunchedEffect(locked, pendingStatement) {
                    val id = pendingStatement
                    if (!locked && id != null) {
                        pendingStatement = null
                        nav.navigate("statement/$id") { launchSingleTop = true }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    // Push/pop slide instead of the default cross-fade; the two tabs swap with no animation.
                    val tabs = setOf("home")
                    val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    // While locked the screens stay composed (so navigation and screen state survive), but
                    // they're hidden from accessibility, and sheets/dialogs/menus keep out via LocalLocked.
                    androidx.compose.runtime.CompositionLocalProvider(LocalLocked provides locked) {
                    Box(Modifier.fillMaxSize().then(if (locked) Modifier.clearAndSetSemantics {} else Modifier)) {
                    // Lets a card face be the same element on Home and Detail (Modifier.sharedCard).
                    SharedTransitionLayout(Modifier.fillMaxSize()) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
                    NavHost(
                        nav, startDestination = "home",
                        enterTransition = {
                            if (initialState.destination.route in tabs && targetState.destination.route in tabs) androidx.compose.animation.EnterTransition.None
                            else androidx.compose.animation.slideInHorizontally(slide) { it }
                        },
                        exitTransition = {
                            if (initialState.destination.route in tabs && targetState.destination.route in tabs) androidx.compose.animation.ExitTransition.None
                            else androidx.compose.animation.slideOutHorizontally(slide) { -it / 4 }
                        },
                        popEnterTransition = { androidx.compose.animation.slideInHorizontally(slide) { -it / 4 } },
                        popExitTransition = { androidx.compose.animation.slideOutHorizontally(slide) { it } },
                    ) {
                        composable("home") {
                          androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                            // Back from Activity returns to Cards, as before. The pages' own handlers (clear search,
                            // close "+ N more") are registered after this one, so they go first.
                            androidx.activity.compose.BackHandler(!locked && pager.currentPage != 0) { switchTab(Tab.Cards) }
                            // One header for both pages (its title and buttons switch with the page); only the
                                // content below swipes.
                                val headers = androidx.compose.runtime.remember { TabHeaders() }
                            androidx.compose.material3.Scaffold(
                                topBar = { SharedTopBar(headers, pager.currentPage) },
                            ) { pad ->
                            // Consume only the top inset: the tab bar and the pages still pad for the navigation bar.
                            Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).consumeWindowInsets(PaddingValues(top = pad.calculateTopPadding()))) {
                                androidx.compose.foundation.pager.HorizontalPager(
                                    pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { Tab.entries[it].route },
                                ) { page ->
                                  androidx.compose.runtime.CompositionLocalProvider(
                                      LocalTabHeaders provides headers, LocalTabPage provides page,
                                      LocalTabSelected provides (pager.currentPage == page),
                                  ) {
                                    when (Tab.entries[page]) {
                                        Tab.Cards -> {
                                        HomeScreen(
                                            cards = cards,
                                            deviceSecure = deviceSecure,
                                            onOpen = { nav.navigate("card/$it") },
                                            onAdd = { nav.navigate("edit/0") },
                                            onAddWith = { start -> nav.navigate(start?.let { "edit/0?start=$it" } ?: "edit/0") },
                                            onEdit = { nav.navigate("edit/$it") },
                                            onLock = { locked = true },
                                            onSettings = { nav.navigate("settings") },
                                            onPin = { c, pin, replacing -> app.repo.setPinned(c, pin, replacing) },
                                            bottomBar = {},
                                        )
                                        }
                                        Tab.Activity -> {
                                        val m = mail
                                        ActivityScreen(
                                            txns = txns,
                                            cards = everyCard,
                                            categories = categories,
                                            statements = statements,
                                            mailConfigured = m?.passwordEnc != null,
                                            lastSync = if (m == null || m.lastSyncAt == 0L) "Not synced yet" else
                                                "Synced ${android.text.format.DateUtils.getRelativeTimeSpanString(m.lastSyncAt)} · ${m.lastResult}",
                                            sync = sync,
                                            ledger = ledger,
                                            onSync = runSync,
                                            onSetupMail = { nav.navigate("mail") },
                                            onOpenStatement = { nav.navigate("statement/$it") },
                                            needsYou = needsYou.size,
                                            onImport = importPdf,
                                            bottomBar = {},
                                        )
                                        }
                                    }
                                  }
                                }
                                // One tab bar over both pages; it follows the swipe.
                                Box(Modifier.align(Alignment.BottomCenter)) {
                                    PillNavBar(Tab.entries[pager.targetPage], ::switchTab)
                                }
                            }
                            }
                          }
                        }
                        composable("card/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("id") ?: 0L
                            androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                            DetailScreen(
                                cw = everyCard.firstOrNull { it.card.id == id },
                                payments = payments,
                                repo = app.repo,
                                statements = statements,
                                onOpenStatement = { nav.navigate("statement/$it") },
                                onBack = { nav.popBackStack() },
                                onEdit = { nav.navigate("edit/$id") },
                            )
                            }
                        }
                        composable("statement/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                            StatementScreen(
                                statementId = entry.arguments?.getLong("id") ?: 0L,
                                ledger = ledger,
                                repo = app.repo,
                                cards = everyCard,
                                categories = categories,
                                statements = statements,
                                payments = payments,
                                onBack = { nav.popBackStack() },
                                // Replace this statement rather than stacking: back still returns to where you came from.
                                onOpenStatement = { id -> nav.navigate("statement/$id") { popUpTo("statement/{id}") { inclusive = true } } },
                            )
                        }
                        composable("mail") {
                            MailScreen(
                                ledger = ledger,
                                log = importLog,
                                cards = cards.orEmpty().map { it.card },
                                needsYou = needsYou,
                                fixKey = fixKey,
                                onFixKeyConsumed = { fixKey = null },
                                onImport = importPdf,
                                onImported = onImported,
                                onOpenStatement = { nav.navigate("statement/$it") },
                                onPasswords = { nav.navigate("passwords") },
                                sync = sync,
                                onSync = runSync,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        // "start" (optional): "scan" or "tap" opens the camera / NFC sheet once the screen is shown (app shortcuts).
                        composable(
                            "edit/{id}?start={start}",
                            arguments = listOf(
                                navArgument("id") { type = NavType.LongType },
                                navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            EditScreen(
                                cardId = entry.arguments?.getLong("id") ?: 0L,
                                repo = app.repo,
                                defaultLeads = app.prefs.defaultLeads,
                                deviceSecure = deviceSecure,
                                onDone = { nav.popBackStack() },
                                startWith = entry.arguments?.getString("start"),
                                // Duplicate found: leave the form and show the card already saved.
                                onOpenCard = { id ->
                                    nav.popBackStack()
                                    nav.navigate("card/$id") { launchSingleTop = true }
                                },
                            )
                        }
                        composable("passwords") { PasswordsScreen(ledger, onBack = { nav.popBackStack() }) }
                        composable("settings") {
                            SettingsScreen(
                                app.prefs, onMail = { nav.navigate("mail") }, onPasswords = { nav.navigate("passwords") }, onBack = { nav.popBackStack() },
                                onArchived = { nav.navigate("archived") }, archivedCount = archived.size,
                            )
                        }
                        composable("archived") {
                            ArchivedScreen(
                                cards = archived,
                                onOpen = { nav.navigate("card/$it") },
                                onRestore = { id -> scope.launch { app.repo.setArchived(id, false) } },
                                onDelete = { id -> scope.launch { app.repo.delete(id) } },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                    }
                    }
                    }
                    }
                    // First run, over Home: until finished or skipped. Anyone who already has cards never sees it.
                    var onboarded by androidx.compose.runtime.remember { mutableStateOf(app.prefs.onboarded) }
                    LaunchedEffect(everyCard) {
                        if (!onboarded && everyCard.isNotEmpty()) { app.prefs.onboarded = true; onboarded = true }
                    }
                    if (!onboarded && everyCard.isEmpty()) {
                        if (cards == null) {
                            // Still loading: cover Home so it doesn't flash before we know.
                            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {}
                        } else {
                            OnboardingScreen(app.prefs) { exit ->
                                app.prefs.onboarded = true
                                onboarded = true
                                if (exit is OnboardingExit.AddCard) nav.navigate(exit.start?.let { "edit/0?start=$it" } ?: "edit/0")
                            }
                        }
                    }
                    if (locked) {
                        // Added after every other Back handler, so it wins: Back never pops the screens behind the lock.
                        androidx.activity.compose.BackHandler { moveTaskToBack(true) }
                        LockScreen(onUnlock = {
                            scope.launch {
                                if (Auth.authenticate(this@MainActivity, "Unlock Cards")) locked = false
                            }
                        }, onBack = { moveTaskToBack(true) })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?, shortcuts: Boolean = true) {
        val id = intent?.getLongExtra(Notifications.EXTRA_OPEN_CARD, -1L) ?: -1L
        if (id > 0) pendingCard = id
        val sid = intent?.getLongExtra(Notifications.EXTRA_OPEN_STATEMENT, -1L) ?: -1L
        if (sid > 0) pendingStatement = sid
        // Launcher shortcuts (res/xml/shortcuts.xml).
        if (shortcuts) when (intent?.action) {
            ACTION_ADD_CARD -> pendingAdd = SHORTCUT_ADD
            ACTION_SCAN_CARD -> pendingAdd = "scan"
            ACTION_TAP_CARD -> pendingAdd = "tap"
        }
        // A PDF shared from another app (e.g. a Gmail attachment).
        if (intent?.action == Intent.ACTION_SEND && intent.type == "application/pdf") {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
            if (uri != null) pendingImport = uri
        }
    }

    override fun onStart() {
        super.onStart()
        deviceSecure = Auth.deviceSecure(this)
        if (!deviceSecure) {
            locked = false
            return
        }
        val timeoutMs = CardsApp.from(this).prefs.lockTimeoutSec * 1000L
        if (backgroundedAt > 0 && !Auth.inProgress && SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) {
            locked = true
        }
        backgroundedAt = 0
    }

    override fun onStop() {
        super.onStop()
        if (!Auth.inProgress && !isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }
}
