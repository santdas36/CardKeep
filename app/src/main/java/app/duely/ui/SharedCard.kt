package app.duely.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

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
