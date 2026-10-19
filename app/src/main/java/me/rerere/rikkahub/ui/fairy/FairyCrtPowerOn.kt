package me.rerere.rikkahub.ui.fairy

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val POWER_ON_MS = 420
private const val LINE_PHASE = 0.3f

/** Once per process: whether the cold-start power-on is still owed. */
object FairyColdStart {
    private var played = false

    /** True exactly once per process. */
    fun claim(): Boolean = !played.also { played = true }
}

/**
 * CRT power-on over the whole page, replayed each time [trigger] grows past
 * zero: the screen starts black, a glowing horizontal line spreads from the
 * center (first 30 %), then the picture opens vertically from that line
 * while a pale flash fades. 420 ms in total. Reduced Motion draws nothing.
 * The overlay takes no input, so touches reach the page underneath.
 */
@Composable
fun FairyCrtPowerOn(trigger: Int, modifier: Modifier = Modifier) {
    if (!LocalAgoraMotionPolicy.current.allowContinuousMotion) return
    val glow = LocalFairyTokens.current.fairyGlow
    val progress = remember { Animatable(if (trigger > 0) 0f else 1f) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(POWER_ON_MS, easing = LinearEasing))
    }
    Spacer(
        modifier.fillMaxSize().drawBehind {
            val p = progress.value
            if (p >= 1f) return@drawBehind
            val mid = size.height / 2f
            val line = 2.dp.toPx()
            if (p < LINE_PHASE) {
                drawRect(Color.Black)
                val t = p / LINE_PHASE
                val w = size.width * t * t * (3f - 2f * t)
                drawGlowLine(glow, mid, (size.width - w) / 2f, w, line * 6f, 0.35f)
                drawGlowLine(Color.White, mid, (size.width - w) / 2f, w, line, 1f)
                return@drawBehind
            }
            val q = (p - LINE_PHASE) / (1f - LINE_PHASE)
            val open = 1f - (1f - q) * (1f - q)
            val shutter = mid * (1f - open)
            drawRect(Color.Black, Offset.Zero, Size(size.width, shutter))
            drawRect(Color.Black, Offset(0f, size.height - shutter), Size(size.width, shutter))
            drawRect(Color(0xFFBFD6FF).copy(alpha = 0.22f * (1f - q)))
            drawGlowLine(glow, mid, 0f, size.width, line * 6f, 0.35f * (1f - q))
            drawGlowLine(Color.White, mid, 0f, size.width, line, 1f - q)
        },
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlowLine(
    color: Color,
    y: Float,
    x: Float,
    width: Float,
    thickness: Float,
    alpha: Float,
) {
    if (alpha <= 0f || width <= 0f) return
    drawRect(
        Brush.verticalGradient(
            0f to color.copy(alpha = 0f),
            0.5f to color.copy(alpha = alpha),
            1f to color.copy(alpha = 0f),
            startY = y - thickness / 2f,
            endY = y + thickness / 2f,
        ),
        Offset(x, y - thickness / 2f),
        Size(width, thickness),
    )
}
