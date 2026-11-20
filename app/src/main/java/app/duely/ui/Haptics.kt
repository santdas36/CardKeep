package app.duely.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** The few haptics the app uses; each maps to the richest platform constant this phone has. */
enum class Haptic { Tick, Snap, Confirm, Reject }

/**
 * Performs a [Haptic] through the view, so the system's touch-feedback setting is respected.
 * API 30 added CONFIRM / REJECT / GESTURE_END; older phones get the nearest older constant.
 */
@Composable
fun rememberHaptics(): (Haptic) -> Unit {
    val view = LocalView.current
    return remember(view) {
        val perform: (Haptic) -> Unit = { h ->
            val modern = Build.VERSION.SDK_INT >= 30
            view.performHapticFeedback(
                when (h) {
                    Haptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
                    Haptic.Snap -> if (modern) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
                    Haptic.Confirm -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                    Haptic.Reject -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                },
            )
        }
        perform
    }
}
