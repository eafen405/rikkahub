package me.rerere.rikkahub.ui.fairy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val FlashColor = Color(0xFF9CC6FF)

/**
 * Brushes for one FairyScreen size, built once in drawWithCache. Frames only
 * translate, scale and fade them, so drawing allocates nothing per frame.
 * The look follows Fairy.gif: a cloudy aura with horizontal CRT bleed, a
 * periodic bloom flash, a rolling bright band and a dark vignette.
 */
internal class FairyScreenPaint(size: Size, val center: Offset, val eyePx: Float, glow: Color) {
    /** Unit-radius radial falloffs, scaled into place per draw. */
    val aura: Brush = unitRadial(glow)
    val flash: Brush = unitRadial(FlashColor)
    val bandHeight = size.height * 0.35f
    val band: Brush = Brush.verticalGradient(
        0f to Color.Transparent,
        0.5f to Color.White.copy(alpha = 0.05f),
        1f to Color.Transparent,
        startY = 0f,
        endY = bandHeight,
    )
    val vignette: Brush = Brush.radialGradient(
        0.45f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.55f),
        center = center,
        radius = hypot(size.width, size.height) / 2f,
    )

    private fun unitRadial(color: Color) = Brush.radialGradient(
        0f to color,
        0.45f to color.copy(alpha = color.alpha * 0.45f),
        1f to color.copy(alpha = 0f),
        center = Offset.Zero,
        radius = 1f,
    )
}

/** Fills an ellipse of radii ([rx], [ry]) at ([cx], [cy]) with a unit brush. */
private fun DrawScope.blob(brush: Brush, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
    if (alpha <= 0f) return
    withTransform({
        translate(cx, cy)
        scale(rx, ry, Offset.Zero)
    }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceAtMost(1f))
    }
}

/**
 * The aura behind the eye at [strength] (glow alpha). A core bloom, three
 * lobes that drift with [drift] (cycles) so the halo reads as smoke rather
 * than a disc, and two flattened streaks for the horizontal CRT bleed.
 */
internal fun DrawScope.drawFairyAura(paint: FairyScreenPaint, strength: Float, drift: Float) {
    val e = paint.eyePx
    val c = paint.center
    blob(paint.aura, c.x, c.y, e * 0.95f, e * 0.95f, strength)
    for (i in 0 until 3) {
        val wobble = sin(2.0 * PI * (drift + i / 3.0)).toFloat()
        val angle = Math.toRadians(-90.0 + (i - 1) * 62.0 + wobble * 10.0)
        val distance = e * (0.36f + 0.05f * wobble)
        blob(
            paint.aura,
            c.x + distance * cos(angle).toFloat(),
            c.y + distance * sin(angle).toFloat(),
            e * 0.5f,
            e * 0.46f,
            strength * (0.5f + 0.12f * wobble),
        )
    }
    val sway = sin(2.0 * PI * drift).toFloat() * e * 0.04f
    blob(paint.aura, c.x + sway, c.y + e * 0.3f, e * 1.4f, e * 0.17f, strength * 0.8f)
    blob(paint.aura, c.x - sway, c.y - e * 0.08f, e * 1.05f, e * 0.09f, strength * 0.35f)
}

/** A soft pale bloom over the whole eye area; [amount] is 0..1. */
internal fun DrawScope.drawFairyFlash(paint: FairyScreenPaint, amount: Float) {
    val e = paint.eyePx
    blob(paint.flash, paint.center.x, paint.center.y, e * 1.15f, e * 1.05f, 0.7f * amount)
}

/** Rolling band at [phase] (cycles, top to bottom), then the vignette. */
internal fun DrawScope.drawFairyCrt(paint: FairyScreenPaint, phase: Float?) {
    if (phase != null) {
        val y = -paint.bandHeight + phase * (size.height + paint.bandHeight)
        withTransform({ translate(0f, y) }) {
            drawRect(paint.band, size = Size(size.width, paint.bandHeight))
        }
    }
    drawRect(paint.vignette)
}

/**
 * Flash envelope over one working cycle: a single smooth bump centered at
 * 0.64 of the cycle (Fairy.gif brightens around frames 75-85 of 122).
 */
internal fun fairyFlashAt(phase: Float): Float {
    val d = (phase - 0.64f) / 0.07f
    return if (d <= -1f || d >= 1f) 0f else ((1.0 + cos(PI * d)) / 2.0).toFloat()
}
