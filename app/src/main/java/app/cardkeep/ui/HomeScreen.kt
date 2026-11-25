package app.cardkeep.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.zIndex
import app.cardkeep.data.Card as CardEntity
import app.cardkeep.data.CardKind
import app.cardkeep.data.isCredit
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Space the floating tab bar needs at the bottom of scrolling content. */
val FloatingBarClearance = 104.dp

/** Cards in the home stack; the rest wait behind "+ N more cards". */
private const val STACK_SIZE = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    cards: List<CardWithDue>?,
    deviceSecure: Boolean,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    /** Add with a start: "scan", "tap" or null (type it in). */
    onAddWith: (String?) -> Unit = { onAdd() },
    onEdit: (Long) -> Unit,
    onLock: () -> Unit,
    onSettings: () -> Unit,
    /** Pins or unpins; false when the pin limit refused it. */
    onPin: suspend (CardEntity, Boolean, Long?) -> Boolean,
    bottomBar: @Composable () -> Unit,
) {
    // Order of the stacked cards, back to front. Empty until first shown; then only gestures change it.
    var stack by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var sheetFor by remember { mutableStateOf<CardWithDue?>(null) }
    var replacing by remember { mutableStateOf<CardEntity?>(null) } // pinning a 4th card
    var category by rememberSaveable { mutableStateOf<String?>(null) } // null = all types
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val pageScroll = rememberScrollState()
    // Saved, so "+ N more" is still open (and the page where it was) after a card's detail and back.
    val st = rememberSaveable(
        // The spread distance comes along too, so the first frame back already has the scroll room the
        // restored page position needs (otherwise the scroll would be clamped before the stack measures).
        saver = androidx.compose.runtime.saveable.Saver<StackState, FloatArray>(
            save = { floatArrayOf(if (it.moreOpen) 1f else 0f, it.dist) },
            restore = { v -> StackState(scope, pageScroll).also { s -> s.dist = v[1]; if (v[0] == 1f) s.restoreOpen() } },
        ),
    ) { StackState(scope, pageScroll) }
    // Search only exists with more cards than the stack holds; drop a leftover query when it goes away.
    val searchable = cards != null && cards.size > STACK_SIZE
    LaunchedEffect(cards?.size) {
        if (cards != null && cards.size <= STACK_SIZE && query.isNotEmpty()) query = ""
    }
    val q = if (searchable) query.trim() else ""
    // Changing the type filter or starting/ending a search folds the stack; coming back to the screen doesn't.
    val view = "$category|${q.isEmpty()}"
    var lastView by rememberSaveable { mutableStateOf(view) }
    LaunchedEffect(view) {
        if (view != lastView) { lastView = view; st.reset() }
    }

    // Locked: sheets and dialogs are windows above the lock cover, so close them.
    val locked = LocalLocked.current
    LaunchedEffect(locked) { if (locked) { sheetFor = null; replacing = null } }
    // Back: first clears the search, then closes "+ N more", then falls through (to the tab / exit).
    val selected = LocalTabSelected.current
    androidx.activity.compose.BackHandler(!locked && selected && q.isEmpty() && st.moreOpen) { st.closeMore() }
    androidx.activity.compose.BackHandler(!locked && selected && searchable && query.isNotEmpty()) { query = "" }

    val actions: @Composable () -> Unit = {
        if (deviceSecure) IconButton(onClick = onLock) { Icon(Icons.Outlined.Lock, "Lock now") }
        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
        FilledIconButton(
            onClick = onAdd, modifier = Modifier.padding(end = 8.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Icon(Icons.Filled.Add, "Add card") }
    }
    // Inside the tab pager the shared top bar shows these; standalone, the screen draws its own.
    val hosted = hostedHeader("Cards", actions)
    Scaffold(
        topBar = {
            if (!hosted) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Cards", style = MaterialTheme.typography.titleLarge) },
                actions = { actions() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = FloatingBarClearance - 24.dp)) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            when {
                cards == null -> {}
                // Same empty slot and Scan / Tap / Type tiles as the last onboarding page.
                cards.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = FloatingBarClearance),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!deviceSecure) LockWarning()
                    EmptyCardSlot(Modifier.fillMaxWidth(0.62f))
                    Spacer(Modifier.height(28.dp))
                    Text("No cards yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add one to start tracking dues.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    AddCardTiles(onAddWith)
                }
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                  val viewport = maxHeight
                  val overscroll = androidx.compose.foundation.rememberOverscrollEffect()
                  val stackedAtTop by remember { derivedStateOf { st.canCycle && st.spread == 0f && !st.moreOpen } }
                  // Searching shows a plain list: no spread, so no extra scroll room for it.
                  val spreadRoom = if (q.isNotEmpty()) 0.dp else with(LocalDensity.current) { st.dist.toDp() }
                  Column(
                    Modifier.fillMaxSize()
                        // The page scroll drives the stack: scrolling down spreads it, scrolling back up folds it.
                        .nestedScroll(st.connection)
                        // Stacked, a pull at the top moves the front card; Android's stretch on top of that
                        // would be a second, competing bounce. Derived, so it only flips at the ends.
                        .verticalScroll(pageScroll, overscrollEffect = if (stackedAtTop) null else overscroll)
                        // Always enough room to scroll the stack fully open, even with little else on the page.
                        .heightIn(min = viewport + spreadRoom)
                        .navigationBarsPadding().padding(bottom = FloatingBarClearance),
                  ) {
                    if (!deviceSecure) Box(Modifier.padding(horizontal = Gutter)) { LockWarning() }
                    // Dues and limits only exist for credit cards.
                    val credit = cards.filter { it.card.isCredit }
                    val kinds = CardKind.all.filter { k -> cards.any { it.card.kind == k } }
                    if (category != null && category !in kinds) category = null
                    val shown = category?.let { k -> cards.filter { it.card.kind == k } } ?: cards
                    if (credit.isNotEmpty()) { Summary(credit); Spacer(Modifier.height(16.dp)) }
                    if (searchable) {
                        SearchField(query, { query = it })
                        Spacer(Modifier.height(12.dp))
                    }
                    if (q.isNotEmpty()) {
                        // Searching: plain results instead of the stack.
                        st.canCycle = false
                        if (st.dist != 0f) st.dist = 0f
                        SearchResults(cards.filter { it.matches(q) }, onOpen) { sheetFor = it }
                        return@Column
                    }
                    if (kinds.size > 1) {
                        Row(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                            kinds.forEach { k ->
                                FilterChip(selected = category == k, onClick = { category = k }, label = { Text(CardKind.label(k)) })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    } else Spacer(Modifier.height(4.dp))

                    // Pinned cards first (up to three), then soonest due; that's already how [cards] is sorted.
                    val members = shown.take(STACK_SIZE)
                    val extras = shown.drop(STACK_SIZE)
                    key(category) {
                        WalletStack(st, members, extras, stack, { order ->
                            val ids = order.toSet()
                            stack = ArrayList(stack.filter { it !in ids } + order)
                        }, onOpen) { sheetFor = it }
                    }
                    if (extras.isNotEmpty()) {
                        TextButton(
                            onClick = { if (st.moreOpen) st.closeMore() else st.openMore() },
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                        ) { Text(if (st.moreOpen) "Show less" else "+ ${extras.size} more card${if (extras.size == 1) "" else "s"}") }
                    } else Spacer(Modifier.height(12.dp))
                    Spacer(Modifier.height(16.dp))
                    if (credit.isNotEmpty()) Upcoming(credit, onOpen)
                  }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter)) { bottomBar() }
        }
    }

    sheetFor?.takeIf { !locked }?.let { cw ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BankBadge(cw.card.issuer, size = 40.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(cw.card.nickname, style = MaterialTheme.typography.titleMedium)
                    Text("${cw.card.issuer} · •• ${cw.card.last4}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ListItem(
                headlineContent = { Text("Open") },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                colors = colors,
                modifier = Modifier.clickable { sheetFor = null; onOpen(cw.card.id) },
            )
            ListItem(
                headlineContent = { Text(if (cw.card.pinned) "Unpin from stack" else "Pin to stack") },
                supportingContent = {
                    Text(if (cw.card.pinned) "Back to soonest-due order" else "Always one of the $MAX_PINS cards in your stack")
                },
                leadingContent = { Icon(Icons.Outlined.PushPin, null) },
                colors = colors,
                modifier = Modifier.clickable {
                    val pin = !cw.card.pinned
                    sheetFor = null
                    val pinnedNow = cards.orEmpty().count { it.card.pinned }
                    if (pin && pinnedNow >= MAX_PINS) { haptic(Haptic.Reject); replacing = cw.card; return@clickable }
                    scope.launch {
                        // The list can be a moment behind the database: if the pin is refused, ask which to replace.
                        if (onPin(cw.card, pin, null)) {
                            haptic(Haptic.Confirm)
                            snackbar.showSnackbar(if (pin) "${cw.card.nickname} pinned to the stack" else "Unpinned")
                        } else {
                            haptic(Haptic.Reject)
                            replacing = cw.card
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Edit") },
                leadingContent = { Icon(Icons.Outlined.Edit, null) },
                colors = colors,
                modifier = Modifier.clickable { sheetFor = null; onEdit(cw.card.id) },
            )
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }

    replacing?.let { card ->
        ReplacePinDialog(
            card, cards.orEmpty().map { it.card }.filter { it.pinned },
            onReplace = { id ->
                replacing = null
                scope.launch {
                    val ok = onPin(card, true, id)
                    haptic(if (ok) Haptic.Confirm else Haptic.Reject)
                    snackbar.showSnackbar(if (ok) "${card.nickname} pinned to the stack" else "Couldn't pin ${card.nickname}")
                }
            },
            onDismiss = { replacing = null },
        )
    }
}

/**
 * Shared between the page's scroll and the stack. The spread is read straight from the page scroll, so
 * the page moves from the first pixel while the cards slide out from under the front card in step, and
 * scrolling back up folds them in the same way. "+ N more" holds the list open until "Show less".
 */
@Stable
private class StackState(private val scope: CoroutineScope, val scroll: androidx.compose.foundation.ScrollState) {
    /** Scroll distance (px) over which the stack spreads fully; set by the stack from its layout. */
    var dist by mutableFloatStateOf(0f)
    private var forced by mutableFloatStateOf(0f) // held open by "+ N more"
    var more by mutableFloatStateOf(0f) // 0 = only the stack, 1 = every card listed
    var moreOpen by mutableStateOf(false)
    var pull by mutableFloatStateOf(0f) // front card's pulled-down distance as shown (rubber-banded)
    /** How far the finger has actually travelled; [pull] follows it 1:1 up to the threshold, then stiffens. */
    var rawPull = 0f
        set(v) { field = v; pull = band(v) }
    /** Beyond [pullThreshold], extra pull approaches this much more and no further (like stretching against a weight). */
    var pullStretch = 0f
    /** Fling physics of the page, for working out where a release would naturally come to rest. */
    var decay: androidx.compose.animation.core.DecayAnimationSpec<Float>? = null
    var canCycle = false
    var onPullRelease: (Float) -> Unit = {}
    /** Pull (px) past which letting go sends the front card back; set by the stack from its layout. */
    var pullThreshold = Float.MAX_VALUE
    /** The pull crossed [pullThreshold] (reset on release); fires [onPullArmed] once per crossing. */
    var armed = false
    var onPullArmed: () -> Unit = {}
    /** The spread snapped to the other state (stacked <-> spread), not just back to where it was. */
    var onSnap: () -> Unit = {}
    private fun band(x: Float): Float {
        if (x <= pullThreshold || pullStretch <= 0f) return x
        val over = x - pullThreshold
        return pullThreshold + pullStretch * (1f - 1f / (1f + over * 0.55f / pullStretch))
    }

    private var settledOpen: Boolean? = null // last resting state; read lazily, as the scroll is restored late

    /** 0 = stacked, 1 = laid out one below the other. */
    val spread: Float
        get() = if (dist <= 1f) forced else max(forced, (scroll.value / dist).coerceIn(0f, 1f))

    private var job: Job? = null
    private var dir = 0 // +1 last moved down the page, -1 up
    private val soft = spring<Float>(dampingRatio = 0.9f, stiffness = 420f)
    // Releases start at the finger's speed, so a little less damping reads as momentum, not wobble.
    private val release = spring<Float>(dampingRatio = 0.86f, stiffness = 380f)

    fun reset() {
        job?.cancel(); forced = 0f; more = 0f; moreOpen = false; rawPull = 0f; armed = false; settledOpen = false
        scope.launch { scroll.scrollTo(0) }
    }

    /** Back on the screen with "+ N more" left open: already open, no animation. */
    fun restoreOpen() {
        forced = 1f; more = 1f; moreOpen = true
    }

    fun openMore() {
        job?.cancel()
        moreOpen = true
        job = scope.launch {
            launch { animate(forced, 1f, animationSpec = soft) { v, _ -> forced = v } }
            animate(more, 1f, animationSpec = soft) { v, _ -> more = v }
        }
    }

    /** "Show less": back to the top and everything folds into the three-card stack in one motion. */
    fun closeMore() {
        job?.cancel()
        job = scope.launch {
            launch { scroll.animateScrollTo(0, soft) }
            launch { animate(more, 0f, animationSpec = soft) { v, _ -> more = v } }
            animate(forced, 0f, animationSpec = soft) { v, _ -> forced = v }
            moreOpen = false
            settledOpen = false
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val y = available.y
            if (y < 0f) dir = 1 else if (y > 0f) dir = -1
            if (settledOpen == null && dist > 1f) settledOpen = scroll.value >= dist
            if (rawPull > 0f && y < 0f) {
                val used = max(y, -rawPull)
                rawPull += used
                if (pull <= pullThreshold) armed = false // pushed back under: no tick, it re-arms on the way out
                return Offset(0f, used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val y = available.y
            // At the very top with the stack closed, a pull down drags the front card (to send it back).
            if (y > 0f && canCycle && spread == 0f && source == NestedScrollSource.UserInput) {
                rawPull += y
                // One tick as the pull passes the point where letting go sends the card back.
                if (!armed && pull > pullThreshold) { armed = true; onPullArmed() }
                return Offset(0f, y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (rawPull > 0f) { onPullRelease(available.y); return available }
            // Released part-way through the spread: carry the finger's speed into the snap instead of letting
            // the page coast to a stop and then snapping from rest (that pause is what felt mechanical).
            val v = scroll.value.toFloat()
            val speed = -available.y // page scroll velocity, px/s (finger up = scrolling down = positive)
            if (!canCycle || moreOpen || dist <= 1f || v <= 0f || v >= dist) return Velocity.Zero
            val rest = decay?.calculateTargetValue(v, speed) ?: v
            if (rest >= dist) return Velocity.Zero // the fling carries through fully open on its own
            val open = if (kotlin.math.abs(speed) < 50f) v > dist * 0.5f else rest > dist * 0.5f
            val target = if (open) dist else 0f
            scroll.scroll {
                var last = v
                animate(v, target, initialVelocity = speed, animationSpec = release) { x, _ -> scrollBy(x - last); last = x }
            }
            if (settledOpen != null && settledOpen != open) onSnap()
            settledOpen = open
            return available
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // Never rest half-spread: finish in the direction you were going. Not while searching (no stack).
            val v = scroll.value.toFloat()
            if (canCycle && !moreOpen && dist > 1f && v > 0f && v < dist) {
                val open = dir > 0 && v > dist * 0.15f || v > dist * 0.85f
                scroll.animateScrollTo(if (open) dist.roundToInt() else 0, soft)
                // Soft tick as it lands, only when it changed state (cancelled by a touch: no tick).
                if (settledOpen != null && settledOpen != open) onSnap()
                settledOpen = open
            } else if (dist > 1f && !moreOpen) {
                settledOpen = v >= dist // came to rest at an end by itself
            }
            return Velocity.Zero
        }
    }
}

/**
 * The home stack. Stacked: three cards, the front one fully visible with the others' top strips behind
 * it; pulling down sends the front card to the back, tapping a card behind brings it forward.
 * Spread (driven by the page scroll): the front card rises to the top and the others slide out from
 * under it into a list, each a little later than the one before (parallax). Extra cards join below.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WalletStack(
    st: StackState,
    members: List<CardWithDue>,
    extras: List<CardWithDue>,
    savedOrder: List<Long>,
    setOrder: (List<Long>) -> Unit,
    onOpen: (Long) -> Unit,
    onLongPress: (CardWithDue) -> Unit,
) {
    val k = members.size
    val byId = members.associateBy { it.card.id }
    // Back -> front. First time: most important at the front. New members join at the front.
    val orderIds = remember(savedOrder, members) {
        val saved = savedOrder.filter { it in byId }
        if (saved.isEmpty()) members.reversed().map { it.card.id }
        else saved + members.map { it.card.id }.filter { it !in saved }.reversed()
    }
    val order = orderIds.mapNotNull { byId[it] }
    val frontId = order.last().card.id
    val haptics = LocalHapticFeedback.current
    val haptic = rememberHaptics()
    val context = LocalContext.current
    val density = LocalDensity.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val stripPx = with(density) { STRIP_HEIGHT.toPx() }
    val gapPx = with(density) { 12.dp.toPx() }
    val liftPx = with(density) { 28.dp.toPx() }
    val flingPx = with(density) { 600.dp.toPx() }
    val follow = spring(dampingRatio = 1f, stiffness = 1400f, visibilityThreshold = with(density) { 6.dp.toPx() })
    val settle = spring<Float>(dampingRatio = 0.82f, stiffness = 700f)
    val scope = rememberCoroutineScope()
    st.decay = androidx.compose.animation.rememberSplineBasedDecay()

    // Every card follows the page scroll directly, like the front card: no per-card lag or easing. The
    // fan-out comes from each card travelling a different distance (deeper cards further) in the same time.
    fun fp(@Suppress("UNUSED_PARAMETER") j: Int) = st.spread

    // One animatable stack slot per card, so a card continues from wherever it is.
    val ys = remember { mutableMapOf<Long, Animatable<Float, AnimationVector1D>>() }
    order.forEachIndexed { i, cw -> ys.getOrPut(cw.card.id) { Animatable(i * stripPx) } }
    var busy by remember { mutableStateOf(false) }
    var raised by remember { mutableStateOf<Long?>(null) }
    var leaving by remember { mutableStateOf<Long?>(null) }
    // Spread enough that a tap opens any card. Derived, so scrolling only recomposes when it flips.
    val opened by remember { derivedStateOf { st.spread > 0.05f } }

    LaunchedEffect(orderIds) {
        orderIds.forEachIndexed { i, id ->
            if (id == leaving) return@forEachIndexed
            launch {
                ys[id]?.let { if (reduceMotion) it.snapTo(i * stripPx) else it.animateTo(i * stripPx, settle) }
            }
        }
    }

    fun toBack(id: Long) = setOrder(listOf(id) + (orderIds - id))
    fun toFront(id: Long) = setOrder(orderIds - id + id)

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        val cardPx = with(density) { (maxWidth / CARD_RATIO).toPx() }
        val frontBase = (k - 1) * stripPx
        val downTravel = cardPx * 0.55f
        val stackH = frontBase + cardPx
        val listH = k * cardPx + (k - 1) * gapPx
        val extraH = extras.size * (cardPx + gapPx)
        st.canCycle = k > 1
        val maxSpread = with(density) { 260.dp.toPx() }
        val d = if (listH - stackH > 1f) min(listH - stackH, maxSpread) else 0f
        if (st.dist != d) st.dist = d

        /** How far (0..1) the front card has been pulled away; the cards behind follow it into their next slots. */
        fun pulled(p: Float) = (p / downTravel).coerceIn(0f, 1f)

        /** Each card's own spread progress: deeper cards start a little later and catch up (parallax). */
        fun progress(j: Int, s: Float): Float {
            val delay = min(j, 3) * 0.04f
            return FastOutSlowInEasing.transform(((s - delay) / (1f - delay)).coerceIn(0f, 1f))
        }

        fun listY(j: Int) = j * (cardPx + gapPx)

        st.pullThreshold = cardPx * 0.22f
        st.pullStretch = cardPx * 0.35f
        val baseShadow = with(density) { 10.dp.toPx() }
        val liftShadow = with(density) { 14.dp.toPx() }
        st.onPullArmed = { haptic(Haptic.Tick) }
        st.onSnap = { haptic(Haptic.Snap) }

        /** Front card falls from wherever the finger let go, slips behind the others and settles at the back. */
        fun sendToBack(velocity: Float, ticked: Boolean) = scope.launch {
            val y = ys[frontId] ?: return@launch
            busy = true
            val shift = pulled(st.pull) * stripPx
            order.forEach { cw -> ys[cw.card.id]?.let { a -> a.snapTo(a.value + if (cw.card.id == frontId) st.pull else shift) } }
            st.rawPull = 0f
            leaving = frontId
            toBack(frontId)
            val exit = max(y.value, frontBase + downTravel)
            if (y.value != exit) { if (reduceMotion) y.snapTo(exit) else y.animateTo(exit, follow, initialVelocity = velocity) }
            // One light tick per cycle: here only if the pull didn't already tick at the threshold (a flick).
            if (!ticked) haptic(Haptic.Tick)
            leaving = null
            busy = false
            if (reduceMotion) y.snapTo(0f) else y.animateTo(0f, settle)
        }

        st.onPullRelease = { v ->
            val ticked = st.armed
            st.armed = false
            if (!busy && (st.pull > st.pullThreshold || v > flingPx)) sendToBack(v, ticked)
            else scope.launch { animate(st.rawPull, 0f, initialVelocity = v, animationSpec = settle) { p, _ -> st.rawPull = p } }
        }

        /** A card from behind rises out of the stack and drops into the front slot; the rest keep their order. */
        fun bringToFront(id: Long) = scope.launch {
            if (busy || id == frontId) return@launch
            val y = ys[id] ?: return@launch
            busy = true
            if (!reduceMotion) y.animateTo(y.value - liftPx, follow)
            raised = id
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            toFront(id)
            busy = false
            kotlinx.coroutines.delay(220)
            if (raised == id) raised = null
        }

        Box(
            Modifier.fillMaxWidth().layout { measurable, constraints ->
                // Height follows the spread at layout time only, so scrolling never recomposes the cards.
                val shownMore = min(st.more, st.spread)
                val h = lerp(stackH, listH, st.spread) + extraH * shownMore
                val hh = h.roundToInt()
                // Measured at the full height so every card, wherever it sits, stays inside the touch area.
                val p = measurable.measure(constraints.copy(minHeight = hh, maxHeight = hh))
                layout(p.width, hh) { p.place(0, 0) }
            },
        ) {
            // Stacked cards.
            order.forEachIndexed { index, cw ->
                key(cw.card.id) {
                    val depth = k - 1 - index // 0 = front, which is also first in the list
                    val isFront = depth == 0
                    val anim = ys[cw.card.id]
                    val baseScale by animateFloatAsState(
                        (1f - depth.coerceAtMost(4) * 0.02f) * (if (raised == cw.card.id) 1.04f else 1f), settle, label = "scale",
                    )
                    // Cards further back sit in the front card's shade; it fades out as the stack spreads.
                    val shade = animateFloatAsState(depth.coerceAtMost(3) * 0.07f, settle, label = "shade")
                    val isRaised = raised == cw.card.id
                    // Coming back from a revealed detail card: this copy finishes the flip as it lands.
                    val landing = FlipBack.pending?.takeIf { it.cardId == cw.card.id }
                    val cardModifier = Modifier
                            .zIndex(if (cw.card.id == leaving) k + 1f else index.toFloat())
                            .offset {
                                val live = if (isFront) st.pull else pulled(st.pull) * stripPx
                                val stacked = (anim?.value ?: (index * stripPx)) + live
                                IntOffset(0, lerp(stacked, listY(depth), fp(depth)).roundToInt())
                            }
                            .graphicsLayer {
                                val p = fp(depth)
                                val s = lerp(baseScale, 1f, p)
                                scaleX = s; scaleY = s
                                // Pulled down, the front card tips a little, pivoting near its top-left corner,
                                // and lifts off the pile (bigger shadow).
                                val pullF = if (isFront) (st.pull / downTravel).coerceIn(0f, 1.5f) else 0f
                                transformOrigin = TransformOrigin(0.15f, 0f)
                                rotationZ = pullF * 2.5f
                                shape = CardShape
                                clip = false
                                shadowElevation = baseShadow + liftShadow * (pullF + if (isRaised) 0.6f else 0f)
                            }
                            .drawWithContent {
                                drawContent()
                                val a = shade.value * (1f - fp(depth).coerceIn(0f, 1f))
                                if (a > 0.004f) drawOutline(CardShape.createOutline(size, layoutDirection, this), Color.Black.copy(alpha = a))
                            }
                            // Grows into the detail card; only cards that are fully showing (they're the ones that open).
                            .onScreen { SharedCardGate.reportHome(cw.card.id, it && (isFront || opened)) }
                            .sharedCard(cw.card.id, enabled = (isFront || opened) && SharedCardGate.allowed(cw.card.id))
                            .semantics {
                                if (isFront && k > 1) customActions = listOf(CustomAccessibilityAction("Send to back") { toBack(cw.card.id); true })
                            }
                            .combinedClickable(
                                onClickLabel = if (isFront) "Open card" else "Bring to front",
                                onLongClickLabel = "Card actions",
                                onClick = { if (isFront || st.spread > 0.05f) onOpen(cw.card.id) else bringToFront(cw.card.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongPress(cw)
                                },
                            )
                    if (landing != null) LandingFlip(cw, landing, cardModifier)
                    else CardFace(cw, ownShadow = false, modifier = cardModifier)
                }
            }
            // Extra cards: tucked under the front card until "+ N more", then laid out below the stack's cards.
            extras.forEachIndexed { i, cw ->
                key(cw.card.id) {
                    val j = k + i
                    CardFace(
                        cw,
                        ownShadow = false,
                        modifier = Modifier
                            .zIndex(-1f - i)
                            // Hidden under the front card until "+ N more": keep them from TalkBack too.
                            .then(if (st.moreOpen) Modifier else Modifier.clearAndSetSemantics {})
                            .offset {
                                val p = min(fp(j), progress(i, st.more))
                                IntOffset(0, lerp(frontBase, listY(j), p).roundToInt())
                            }
                            .graphicsLayer {
                                val p = min(fp(j), progress(i, st.more))
                                alpha = p
                                val s = lerp(0.94f, 1f, p)
                                scaleX = s; scaleY = s
                                shape = CardShape
                                clip = false
                                shadowElevation = baseShadow
                            }
                            .onScreen { SharedCardGate.reportHome(cw.card.id, it && st.moreOpen) }
                            .sharedCard(cw.card.id, enabled = st.moreOpen && SharedCardGate.allowed(cw.card.id))
                            .combinedClickable(
                                enabled = st.moreOpen,
                                onClickLabel = "Open card",
                                onLongClickLabel = "Card actions",
                                onClick = { onOpen(cw.card.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongPress(cw)
                                },
                            ),
                    )
                }
            }
        }
    }
}

private fun CardWithDue.matches(q: String): Boolean {
    val c = card
    return listOf(c.nickname, c.issuer, c.network, CardKind.label(c.kind), c.notes).any { it.contains(q, ignoreCase = true) } ||
        (q.all(Char::isDigit) && c.last4.contains(q))
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value, onChange,
        placeholder = { Text("Search cards, banks, last 4") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Clear search") }
        },
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResults(found: List<CardWithDue>, onOpen: (Long) -> Unit, onLongPress: (CardWithDue) -> Unit) {
    if (found.isEmpty()) {
        Text(
            "No cards match", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }
    Panel(Modifier.padding(horizontal = Gutter), padded = false) {
        found.forEachIndexed { i, cw ->
            if (i > 0) RowDivider()
            val c = cw.card
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { onOpen(c.id) }, onLongClick = { onLongPress(cw) })
                    .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BankBadge(c.issuer, size = 34.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(c.nickname, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${c.issuer} · •• ${c.last4}" + if (c.isCredit) "" else " · ${CardKind.label(c.kind)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (c.isCredit) Text(Money.inr(c.outstandingPaise), style = MaterialTheme.typography.bodyLarge.tabular, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Summary(cards: List<CardWithDue>) {
    val outstanding = cards.sumOf { it.card.outstandingPaise }
    val limit = cards.sumOf { it.card.limitPaise }
    // Totals count every credit card; the next due only those with a due date set.
    val next = cards.filter { it.card.billingSet && !it.due.lastCyclePaid }.minByOrNull { it.due.dueDate }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Total outstanding", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Money.inr(outstanding), style = MaterialTheme.typography.displaySmall.tabular)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                if (limit > 0) append("${(outstanding * 100 / limit)}% of ${Money.inr(limit)} limit")
                if (next != null) {
                    if (isNotEmpty()) append("  ·  ")
                    append("next due ${Dates.short(next.due.dueDate)}")
                }
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LockWarning() {
    Panel(Modifier.padding(bottom = 16.dp), color = Amber.copy(alpha = 0.12f)) {
        Text(
            "No screen lock is set. Set a PIN, pattern or fingerprint in system settings to lock Cards and store card numbers.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Upcoming(cards: List<CardWithDue>, onOpen: (Long) -> Unit) {
    val next = cards.filter { it.card.billingSet && !it.due.lastCyclePaid }.sortedBy { it.due.dueDate }.take(3)
    if (next.isEmpty()) return
    SectionTitle("Upcoming")
    Panel(Modifier.padding(horizontal = Gutter), padded = false) {
        next.forEachIndexed { i, cw ->
            if (i > 0) RowDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(cw.card.id) }.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BankBadge(cw.card.issuer, size = 34.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(cw.card.nickname, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${Dates.weekday(cw.due.dueDate)} · ${Dates.relative(cw.due.daysLeft)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            cw.due.overdue || cw.due.daysLeft <= 2 -> Danger
                            cw.due.daysLeft <= 5 -> Amber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(Money.inr(cw.card.outstandingPaise), style = MaterialTheme.typography.bodyLarge.tabular, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) = SectionHeader(text, Modifier.padding(horizontal = Gutter))

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium.tabular)
    }
}
