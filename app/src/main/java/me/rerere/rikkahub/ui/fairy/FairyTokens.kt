package me.rerere.rikkahub.ui.fairy

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Fixed Fairy dark tokens that sit outside the Material3
 * [androidx.compose.material3.ColorScheme] slots. Provided by [AgoraTheme] via
 * [LocalFairyTokens]; components read from here instead of hard-coding values.
 *
 * Blue marks only Fairy's identity and working state; generic interaction
 * uses the ice-white [primary].
 */
@Immutable
data class FairyTokens(
    /** Page vertical gradient: top and bottom stops. */
    val bgTop: Color = Color(0xFF0B1733),
    val bgBottom: Color = Color(0xFF050A18),
    /** Page scanlines: 1 px lines on a 3 dp period. */
    val scanline: Color = Color.White.copy(alpha = 0.025f),
    /** Fairy window screen: radial center and edge. */
    val screenCenter: Color = Color(0xFF0D2257),
    val screenEdge: Color = Color(0xFF050B1F),
    /** Window bar, composer, dialogs, sheets, menus. */
    val panel: Color = Color(0xFF0E1A36).copy(alpha = 0.92f),
    /** File cards, quick prompts, list items, secondary capsules. */
    val pill: Color = Color(0xFF16244A),
    /** 1 dp panel outline, window bar bottom edge, dividers. */
    val hairline: Color = Color.White.copy(alpha = 0.08f),
    /** Fairy identity: emblem ring, resting accent bar, relay badge. */
    val fairyBlue: Color = Color(0xFF1F55E0),
    /** Fairy working state: glow peak, active accent bar, thinking dots. */
    val fairyGlow: Color = Color(0xFF3D8BFF),
    /** Links in Fairy's text, activity hint text. */
    val fairyLink: Color = Color(0xFF8FB8FF),
    /** Send key, primary buttons, switches, generic progress. */
    val primary: Color = Color(0xFFEAF0FF),
    val onPrimary: Color = Color(0xFF0A1633),
    /** User glass bubble fill and its 1 dp outline. */
    val userBubble: Color = Color.White.copy(alpha = 0.12f),
    val userBubbleBorder: Color = Color.White.copy(alpha = 0.20f),
    /** Code blocks, inline code and quote backgrounds. */
    val codeSurface: Color = Color.White.copy(alpha = 0.06f),
    /** Online status dot, success. */
    val online: Color = Color(0xFF5BE49B),
    /** Errors and the stop key. */
    val danger: Color = Color(0xFFFF5A4E),
    val onDanger: Color = Color.White,
    val textPrimary: Color = Color(0xFFEAF0FF),
    val textMuted: Color = Color(0xFF8C9AB8),
    val panelShape: Shape = RoundedCornerShape(20.dp),
    val screenShape: Shape = RoundedCornerShape(14.dp),
    val userBubbleShape: Shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 6.dp,
        bottomEnd = 18.dp,
        bottomStart = 18.dp,
    ),
    /** MiSans Heavy subset: titles, nameplates, buttons. */
    val titleFontFamily: FontFamily = TitleFamily,
    /** Outfit 900: small latin labels, HUD words and numbers. */
    val labelFontFamily: FontFamily = LabelFamily,
) {
    /** Top-centered radial ambient light on the page. */
    val ambientGlow: Color get() = fairyGlow.copy(alpha = 0.16f)

    /** Opaque panel for surfaces that must not show content through. */
    val panelOpaque: Color get() = bgTop
}

val LocalFairyTokens = staticCompositionLocalOf { FairyTokens() }
