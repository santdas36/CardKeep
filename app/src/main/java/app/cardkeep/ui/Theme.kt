package app.cardkeep.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cardkeep.R

// ---------- Type ----------

/** Inter (variable font, bundled; OFL). One family everywhere, card faces included. */
val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold),
    Font(R.font.inter, FontWeight.ExtraBold),
    Font(R.font.inter, FontWeight.Black),
)

private val base = Typography()

private fun TextStyle.inter(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    copy(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

/**
 * One scale for the whole app. Three weights: Normal for reading, Medium for labels and row titles,
 * SemiBold for headings and amounts. Inter reads best slightly tightened at large sizes.
 */
private val Type = Typography(
    displayLarge = base.displayLarge.inter(FontWeight.SemiBold, 52, 60, -1.2),
    displayMedium = base.displayMedium.inter(FontWeight.SemiBold, 44, 52, -1.0),
    displaySmall = base.displaySmall.inter(FontWeight.SemiBold, 36, 44, -0.8),
    headlineLarge = base.headlineLarge.inter(FontWeight.SemiBold, 32, 40, -0.6),
    headlineMedium = base.headlineMedium.inter(FontWeight.SemiBold, 28, 36, -0.5),
    headlineSmall = base.headlineSmall.inter(FontWeight.SemiBold, 24, 32, -0.4),
    titleLarge = base.titleLarge.inter(FontWeight.SemiBold, 21, 28, -0.3),
    titleMedium = base.titleMedium.inter(FontWeight.SemiBold, 16, 24, -0.1),
    titleSmall = base.titleSmall.inter(FontWeight.SemiBold, 14, 20),
    bodyLarge = base.bodyLarge.inter(FontWeight.Normal, 16, 24, -0.1),
    bodyMedium = base.bodyMedium.inter(FontWeight.Normal, 14, 20),
    bodySmall = base.bodySmall.inter(FontWeight.Normal, 12, 16, 0.1),
    labelLarge = base.labelLarge.inter(FontWeight.Medium, 14, 20),
    labelMedium = base.labelMedium.inter(FontWeight.Medium, 12, 16, 0.2),
    labelSmall = base.labelSmall.inter(FontWeight.Medium, 11, 16, 0.4),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Tabular figures so amounts line up. */
val TextStyle.tabular get() = copy(fontFeatureSettings = "tnum")

// ---------- Color ----------

/** Status colours. They follow the palette and light/dark so they stay readable on both. */
@Immutable
data class StatusColors(val good: Color, val warn: Color, val bad: Color)

private val LocalStatus = staticCompositionLocalOf { StatusColors(Color(0xFF3DD68C), Color(0xFFF2B440), Color(0xFFFF6B6B)) }

val Good: Color @Composable @ReadOnlyComposable get() = LocalStatus.current.good
val Amber: Color @Composable @ReadOnlyComposable get() = LocalStatus.current.warn
val Danger: Color @Composable @ReadOnlyComposable get() = LocalStatus.current.bad

/** One side (dark or light) of a palette. */
internal data class Tones(
    val bg: Long, val card: Long, val cardHigh: Long, val primary: Long, val onPrimary: Long,
    val container: Long, val onContainer: Long, val text: Long, val sub: Long, val line: Long, val lineSoft: Long,
    val good: Long, val warn: Long, val bad: Long,
)

/** A selectable look. [swatch] is what the picker shows. */
class Palette internal constructor(val id: String, val name: String, val swatch: Color, private val dark: Tones?, private val light: Tones?) {
    val dynamic get() = dark == null

    internal fun scheme(isDark: Boolean): ColorScheme? {
        val t = (if (isDark) dark else light) ?: return null
        val c = { v: Long -> Color(v) }
        return if (isDark) darkColorScheme(
            primary = c(t.primary), onPrimary = c(t.onPrimary),
            primaryContainer = c(t.container), onPrimaryContainer = c(t.onContainer),
            secondary = c(t.primary), onSecondary = c(t.onPrimary),
            secondaryContainer = c(t.container), onSecondaryContainer = c(t.onContainer),
            tertiary = c(t.primary),
            background = c(t.bg), onBackground = c(t.text), surface = c(t.bg), onSurface = c(t.text),
            surfaceVariant = c(t.cardHigh), onSurfaceVariant = c(t.sub),
            surfaceContainerLowest = c(t.bg), surfaceContainerLow = c(t.card), surfaceContainer = c(t.card),
            surfaceContainerHigh = c(t.card), surfaceContainerHighest = c(t.cardHigh),
            outline = c(t.line), outlineVariant = c(t.lineSoft), error = c(t.bad),
            inverseSurface = c(t.text), inverseOnSurface = c(t.bg), inversePrimary = c(t.primary),
        ) else lightColorScheme(
            primary = c(t.primary), onPrimary = c(t.onPrimary),
            primaryContainer = c(t.container), onPrimaryContainer = c(t.onContainer),
            secondary = c(t.primary), onSecondary = c(t.onPrimary),
            secondaryContainer = c(t.container), onSecondaryContainer = c(t.onContainer),
            tertiary = c(t.primary),
            background = c(t.bg), onBackground = c(t.text), surface = c(t.bg), onSurface = c(t.text),
            surfaceVariant = c(t.cardHigh), onSurfaceVariant = c(t.sub),
            surfaceContainerLowest = c(t.card), surfaceContainerLow = c(t.card), surfaceContainer = c(t.card),
            surfaceContainerHigh = c(t.card), surfaceContainerHighest = c(t.cardHigh),
            outline = c(t.line), outlineVariant = c(t.lineSoft), error = c(t.bad),
            inverseSurface = c(t.text), inverseOnSurface = c(t.bg), inversePrimary = c(t.primary),
        )
    }

    internal fun status(isDark: Boolean): StatusColors {
        val t = (if (isDark) dark else light) ?: return if (isDark) DarkStatus else LightStatus
        return StatusColors(Color(t.good), Color(t.warn), Color(t.bad))
    }
}

private val DarkStatus = StatusColors(Color(0xFF3DD68C), Color(0xFFF2B440), Color(0xFFFF6B6B))
private val LightStatus = StatusColors(Color(0xFF16955A), Color(0xFFB97509), Color(0xFFD7373F))

// Shared light/dark neutrals per family keep every palette equally calm; only the accent and tint change.
private fun darkTones(bg: Long, card: Long, cardHigh: Long, primary: Long, onPrimary: Long, container: Long, onContainer: Long, text: Long, sub: Long, line: Long, lineSoft: Long) =
    Tones(bg, card, cardHigh, primary, onPrimary, container, onContainer, text, sub, line, lineSoft, 0xFF3DD68C, 0xFFF2B440, 0xFFFF6B6B)

private fun lightTones(bg: Long, cardHigh: Long, primary: Long, container: Long, onContainer: Long, text: Long, sub: Long, line: Long, lineSoft: Long) =
    Tones(bg, 0xFFFFFFFF, cardHigh, primary, 0xFFFFFFFF, container, onContainer, text, sub, line, lineSoft, 0xFF16955A, 0xFFB97509, 0xFFD7373F)

object Palettes {
    val all: List<Palette> = buildList {
        add(Palette("indigo", "Indigo", Color(0xFF8EA2FF),
            darkTones(0xFF0E1016, 0xFF171A23, 0xFF20242F, 0xFF8EA2FF, 0xFF0E1530, 0xFF232B4A, 0xFFDDE3FF, 0xFFECEEF4, 0xFF9097A6, 0xFF3A3F4C, 0xFF242833),
            lightTones(0xFFF5F6FA, 0xFFECEEF5, 0xFF3B50D9, 0xFFE4E8FF, 0xFF101C55, 0xFF12141A, 0xFF5F6676, 0xFFC8CCD8, 0xFFE5E7EE)))
        add(Palette("emerald", "Emerald", Color(0xFF4FD1A1),
            darkTones(0xFF0F1112, 0xFF191C1D, 0xFF222627, 0xFF4FD1A1, 0xFF04241A, 0xFF173A2E, 0xFFCDF5E5, 0xFFECEFEE, 0xFF93A09B, 0xFF39403E, 0xFF242928),
            lightTones(0xFFF4F6F5, 0xFFEAEEEC, 0xFF0E8A62, 0xFFD5F2E6, 0xFF04382A, 0xFF121615, 0xFF5D6965, 0xFFC7CFCB, 0xFFE3E8E6)))
        add(Palette("coral", "Coral", Color(0xFFFF8A65),
            darkTones(0xFF121014, 0xFF1C191F, 0xFF262229, 0xFFFF8A65, 0xFF2A0E05, 0xFF3D2219, 0xFFFFDDD1, 0xFFF1ECEF, 0xFFA3989F, 0xFF443C42, 0xFF2A252C),
            lightTones(0xFFFAF6F4, 0xFFF2ECE9, 0xFFD9572F, 0xFFFFE3D9, 0xFF4A1607, 0xFF1A1416, 0xFF6E6266, 0xFFD8CDC9, 0xFFEDE5E2)))
        add(Palette("violet", "Violet", Color(0xFFB39DFF),
            darkTones(0xFF0F0E15, 0xFF1A1824, 0xFF23202F, 0xFFB39DFF, 0xFF170F33, 0xFF2D2550, 0xFFE6DEFF, 0xFFEEECF5, 0xFF9A96AA, 0xFF3D3A4C, 0xFF262433),
            lightTones(0xFFF6F5FA, 0xFFEDEBF5, 0xFF6A4FD8, 0xFFE9E3FF, 0xFF221357, 0xFF14121C, 0xFF635F73, 0xFFCDC9D9, 0xFFE7E4EF)))
        add(Palette("ocean", "Ocean", Color(0xFF5CC8F2),
            darkTones(0xFF0C1115, 0xFF151C22, 0xFF1E262D, 0xFF5CC8F2, 0xFF032130, 0xFF16374A, 0xFFD2EEFB, 0xFFEAF0F4, 0xFF8E9CA6, 0xFF36414A, 0xFF212A31),
            lightTones(0xFFF3F7F9, 0xFFE8EEF2, 0xFF0B7FB0, 0xFFD6EEF9, 0xFF03344A, 0xFF10161A, 0xFF5A6873, 0xFFC5D0D7, 0xFFE1E8EC)))
        add(Palette("rose", "Rose", Color(0xFFFF85AE),
            darkTones(0xFF130F12, 0xFF1E181C, 0xFF282026, 0xFFFF85AE, 0xFF33061A, 0xFF44202F, 0xFFFFDAE7, 0xFFF3ECEF, 0xFFA6969E, 0xFF463B41, 0xFF2C252A),
            lightTones(0xFFFBF5F7, 0xFFF3EAEE, 0xFFC93A6E, 0xFFFFE0EB, 0xFF4D0E26, 0xFF1A1316, 0xFF71606A, 0xFFDBCBD2, 0xFFEFE3E8)))
        add(Palette("gold", "Gold", Color(0xFFE6B450),
            darkTones(0xFF11100D, 0xFF1C1A16, 0xFF26231D, 0xFFE6B450, 0xFF2B1D00, 0xFF3A301A, 0xFFF8E7C2, 0xFFF1EEE8, 0xFFA29C90, 0xFF433F36, 0xFF2A2721),
            lightTones(0xFFF9F7F2, 0xFFF0ECE3, 0xFF9A6A08, 0xFFF7EBCC, 0xFF3A2700, 0xFF17150F, 0xFF6B6558, 0xFFD6D0C2, 0xFFEBE6DB)))
        add(Palette("mono", "Mono", Color(0xFFD9DCE1),
            darkTones(0xFF0D0E10, 0xFF17181B, 0xFF212226, 0xFFE6E8EC, 0xFF111214, 0xFF2A2C31, 0xFFF0F1F3, 0xFFEDEEF1, 0xFF9497A0, 0xFF3B3D43, 0xFF25272B),
            lightTones(0xFFF5F5F6, 0xFFEBEBED, 0xFF1C1D21, 0xFFE4E5E8, 0xFF111214, 0xFF121316, 0xFF62646B, 0xFFCACBD0, 0xFFE5E5E8)))
        // Wallpaper colours on Android 12+.
        if (Build.VERSION.SDK_INT >= 31) add(Palette("dynamic", "Wallpaper", Color(0xFF9AA5B1), null, null))
    }

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

/** Appearance chosen in Settings; held as state so a change re-themes at once. */
object Appearance {
    var paletteId by mutableStateOf("indigo")
    /** "system", "light" or "dark". */
    var mode by mutableStateOf("system")
}

@Composable
fun CardsTheme(content: @Composable () -> Unit) {
    val dark = when (Appearance.mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val palette = Palettes.byId(Appearance.paletteId)
    val scheme = palette.scheme(dark) ?: if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else Palettes.all.first().scheme(dark)!!
    // Status/nav bar icons follow the app's mode, not the system's (they differ when forced light/dark).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalStatus provides palette.status(dark)) {
        MaterialTheme(colorScheme = scheme, typography = Type, shapes = Shape, content = content)
    }
}
