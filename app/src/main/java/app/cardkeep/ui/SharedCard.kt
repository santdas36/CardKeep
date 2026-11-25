package app.cardkeep.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** The SharedTransitionLayout around the NavHost; null outside it (previews, other hosts). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The nav destination's enter/exit scope, so a card can tell which side of the transition it is on. */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

// Same timing as the push/pop slide, so the card lands as the screen does.
@OptIn(ExperimentalSharedTransitionApi::class)
private val CardBounds = BoundsTransform { _, _ -> tween(300, easing = FastOutSlowInEasing) }

/**
 * Marks a card face as the same card on both screens (key per card id): on push it grows from the stack
 * into the detail card, and back again on pop. A no-op without both scopes, or when [enabled] is false
 * (cards that are hidden or only peeking out of the stack shouldn't fly out from under the front one).
 * CardFace clips itself to CardShape and draws its own shadow, so the overlay needs no extra clip.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(id: Long, enabled: Boolean = true): Modifier {
    val shared = LocalSharedTransitionScope.current
    val anim = LocalNavAnimatedScope.current
    if (shared == null || anim == null || !enabled) return this
    return with(shared) {
        this@sharedCard.sharedElement(rememberSharedContentState("card-$id"), anim, CardBounds)
    }
}

/**
 * Whether a card may fly between Home and its detail screen: only when it's on screen at both ends (the
 * detail card not scrolled away, the home card actually showing). Otherwise screens just slide.
 * Positions are only recorded while a screen is settled, so a running transition never flips the answer.
 */
object SharedCardGate {
    private val homeVisible = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()
    var detailVisible by androidx.compose.runtime.mutableStateOf(true)
        private set

    fun allowed(id: Long) = detailVisible && homeVisible[id] == true

    fun reportHome(id: Long, visible: Boolean) {
        if (homeVisible[id] != visible) homeVisible[id] = visible
        // Home is settled, so no detail screen is up: the next one starts in view.
        if (!detailVisible) detailVisible = true
    }

    fun reportDetail(visible: Boolean) { if (detailVisible != visible) detailVisible = visible }
}

/** Reports whether most (60%) of this element is on screen, but only while its screen isn't animating. */
@Composable
fun Modifier.onScreen(report: (Boolean) -> Unit): Modifier {
    val anim = LocalNavAnimatedScope.current
    return onGloballyPositioned { c ->
        val t = anim?.transition
        if (t != null && (t.currentState != EnterExitState.Visible || t.targetState != EnterExitState.Visible)) return@onGloballyPositioned
        val h = c.size.height
        if (h <= 0) return@onGloballyPositioned
        report(c.boundsInWindow().height / h >= 0.6f)
    }
}
