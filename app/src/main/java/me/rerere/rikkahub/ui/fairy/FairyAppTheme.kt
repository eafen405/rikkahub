package me.rerere.rikkahub.ui.fairy

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat

private val FairyBgTop = Color(0xFF0B1733)
private val FairyBgBottom = Color(0xFF050A18)
private val FairyPanel = Color(0xFF0E1A36)
private val FairyPill = Color(0xFF16244A)
private val FairyIce = Color(0xFFEAF0FF)
private val FairyNavy = Color(0xFF0A1633)
private val FairyBlue = Color(0xFF1F55E0)
private val FairyGlow = Color(0xFF3D8BFF)
private val FairyLink = Color(0xFF8FB8FF)
private val FairyDanger = Color(0xFFFF5A4E)
private val FairyMuted = Color(0xFF8C9AB8)

/**
 * The fixed Fairy dark palette. Product surfaces always use this scheme: it
 * never follows the wallpaper, the system light/dark setting or a user preset.
 * Surfaces step panel -> pill; generic interaction is ice white on navy.
 */
val FairyColorScheme: ColorScheme = ColorScheme(
    primary = FairyIce, onPrimary = FairyNavy,
    primaryContainer = FairyPill, onPrimaryContainer = FairyIce,
    secondary = FairyLink, onSecondary = FairyNavy,
    secondaryContainer = FairyPill, onSecondaryContainer = FairyIce,
    tertiary = FairyBlue, onTertiary = FairyIce,
    tertiaryContainer = FairyBlue, onTertiaryContainer = FairyIce,
    error = FairyDanger, onError = FairyNavy,
    errorContainer = Color(0xFF3A1622), onErrorContainer = Color(0xFFFFB4AC),
    background = FairyBgBottom, onBackground = FairyIce,
    surface = FairyBgBottom, onSurface = FairyIce,
    surfaceVariant = FairyPill, onSurfaceVariant = FairyMuted,
    outline = Color(0xFF3A4666), outlineVariant = Color(0xFF1E2A47),
    inversePrimary = FairyBlue,
    inverseSurface = FairyIce, inverseOnSurface = FairyNavy,
    // Tonal elevation must not tint the navy panels.
    surfaceTint = Color.Transparent, scrim = Color.Black,
    surfaceDim = FairyBgBottom, surfaceBright = Color(0xFF1C2B55),
    surfaceContainerLowest = Color(0xFF081226),
    surfaceContainerLow = FairyBgTop,
    surfaceContainer = FairyPanel,
    surfaceContainerHigh = Color(0xFF122043),
    surfaceContainerHighest = FairyPill,
    primaryFixed = FairyIce, primaryFixedDim = Color(0xFFC9D6F5),
    onPrimaryFixed = FairyNavy, onPrimaryFixedVariant = FairyNavy,
    secondaryFixed = FairyLink, secondaryFixedDim = Color(0xFF6E9BE8),
    onSecondaryFixed = FairyNavy, onSecondaryFixedVariant = FairyNavy,
    tertiaryFixed = FairyBlue, tertiaryFixedDim = FairyGlow,
    onTertiaryFixed = FairyIce, onTertiaryFixedVariant = FairyIce,
)

/**
 * Display, headline, title and label tiers use the Fairy title face; the body
 * tiers are left to the chat-font setting so message reading stays adjustable.
 */
private fun fairyTypography(): Typography {
    fun TextStyle.withFamily(f: androidx.compose.ui.text.font.FontFamily) = copy(fontFamily = f)
    fun TextStyle.tier(size: Float, line: Float) = copy(fontSize = size.sp, lineHeight = line.sp)
    return Typography(
        displayLarge = Typography().displayLarge.withFamily(TitleFamily),
        displayMedium = Typography().displayMedium.withFamily(TitleFamily),
        displaySmall = Typography().displaySmall.withFamily(TitleFamily),
        headlineLarge = Typography().headlineLarge.withFamily(TitleFamily),
        headlineMedium = Typography().headlineMedium.withFamily(TitleFamily),
        headlineSmall = Typography().headlineSmall.withFamily(TitleFamily),
        titleLarge = Typography().titleLarge.withFamily(TitleFamily),
        titleMedium = Typography().titleMedium.withFamily(TitleFamily),
        titleSmall = Typography().titleSmall.withFamily(TitleFamily),
        labelLarge = Typography().labelLarge.withFamily(TitleFamily),
        labelMedium = Typography().labelMedium.withFamily(TitleFamily),
        labelSmall = Typography().labelSmall.withFamily(TitleFamily).tier(11f, 15f),
    )
}

/** Fairy palette, typography and expressive motion shared by the client. */
@Composable
fun FairyAppTheme(content: @Composable () -> Unit) {
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    val tokens = remember { FairyTokens() }
    val typography = remember { fairyTypography() }
    CompositionLocalProvider(
        LocalFairyTokens provides tokens,
        LocalDarkMode provides true,
        LocalContentColor provides tokens.textPrimary,
    ) {
        MaterialExpressiveTheme(
            motionScheme = MotionScheme.expressive(),
            colorScheme = FairyColorScheme,
            typography = typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(20.dp),
            ),
            content = { ProvideAgoraMotionPolicy(appReduceMotion = false, content = content) },
        )
    }
}
