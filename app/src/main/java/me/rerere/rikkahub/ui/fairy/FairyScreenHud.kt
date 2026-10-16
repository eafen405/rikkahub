package me.rerere.rikkahub.ui.fairy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val HUD_CAPTION = "HDD // FAIRY"
private const val HUD_BARS = 9

/**
 * Fixed decorative status word per presence. Generic by design: the HUD
 * never shows reasoning, tool names or message content.
 */
internal fun fairyHudStatus(presence: FairyPresence): String = when (presence) {
    FairyPresence.IDLE -> "STANDBY"
    FairyPresence.THINKING -> "ANALYZING"
    FairyPresence.SPEAKING -> "RESPONDING"
    FairyPresence.CONNECTING -> "LINKING"
    FairyPresence.OFFLINE -> "NO SIGNAL"
}

/** Measured HUD text and metrics for one screen size, built in drawWithCache. */
internal class FairyHudLayout(
    measurer: TextMeasurer,
    density: Density,
    family: FontFamily,
    status: String,
    val accent: Color,
    val muted: Color,
) {
    val caption: TextLayoutResult = measurer.measure(
        HUD_CAPTION,
        TextStyle(fontFamily = family, fontSize = 10.sp, letterSpacing = 1.5.sp, color = muted),
    )
    val status: TextLayoutResult = measurer.measure(
        status,
        TextStyle(fontFamily = family, fontSize = 13.sp, letterSpacing = 1.2.sp, color = accent),
    )
    val inset: Float
    val arm: Float
    val stroke: Float
    val pad: Float
    val dot: Float
    val bar: Float
    val barGap: Float
    val barMax: Float

    init {
        with(density) {
            inset = 7.dp.toPx()
            arm = 10.dp.toPx()
            stroke = 1.5.dp.toPx()
            pad = 16.dp.toPx()
            dot = 3.dp.toPx()
            bar = 3.dp.toPx()
            barGap = 2.dp.toPx()
            barMax = 14.dp.toPx()
        }
    }
}

/**
 * HUD over the screen: corner brackets, the caption, the status word with
 * cycling dots and a level meter. [phase] (cycles) animates dots and bars,
 * [level] (0..1) scales the meter; a null phase draws the still frame.
 */
internal fun DrawScope.drawFairyHud(hud: FairyHudLayout, phase: Float?, level: Float) {
    val bracket = hud.accent.copy(alpha = 0.5f)
    val i = hud.inset
    val a = hud.arm
    val s = hud.stroke
    val w = size.width
    val h = size.height
    // Four L-shaped corner brackets.
    drawRect(bracket, Offset(i, i), Size(a, s))
    drawRect(bracket, Offset(i, i), Size(s, a))
    drawRect(bracket, Offset(w - i - a, i), Size(a, s))
    drawRect(bracket, Offset(w - i - s, i), Size(s, a))
    drawRect(bracket, Offset(i, h - i - s), Size(a, s))
    drawRect(bracket, Offset(i, h - i - a), Size(s, a))
    drawRect(bracket, Offset(w - i - a, h - i - s), Size(a, s))
    drawRect(bracket, Offset(w - i - s, h - i - a), Size(s, a))

    drawText(hud.caption, topLeft = Offset(hud.pad, hud.pad - s))
    val statusTop = h - hud.pad - hud.status.size.height + s
    drawText(hud.status, topLeft = Offset(hud.pad, statusTop))
    // Cycling dots after the status word: 0..3 lit on a 4-step loop.
    val lit = if (phase == null) 3 else ((phase * 4f).toInt() % 4)
    val dotY = statusTop + hud.status.lastBaseline - hud.dot
    var x = hud.pad + hud.status.size.width + hud.dot
    repeat(3) { k ->
        drawRect(hud.accent.copy(alpha = if (k < lit) 0.9f else 0.2f), Offset(x, dotY), Size(hud.dot, hud.dot))
        x += hud.dot * 2f
    }
    // Level meter, right-aligned on the status baseline.
    val base = statusTop + hud.status.lastBaseline
    var bx = w - hud.pad - HUD_BARS * (hud.bar + hud.barGap) + hud.barGap
    repeat(HUD_BARS) { k ->
        val wave = if (phase == null) {
            0.5f
        } else {
            abs(sin(2.0 * PI * (phase * 2.0 + k * 0.13)) * sin(2.0 * PI * (phase + k * 0.29))).toFloat()
        }
        val height = hud.barMax * (0.18f + 0.82f * wave * level)
        drawRect(hud.accent.copy(alpha = 0.35f + 0.5f * level), Offset(bx, base - height), Size(hud.bar, height))
        bx += hud.bar + hud.barGap
    }
}
