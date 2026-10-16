package me.rerere.rikkahub.ui.fairy

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Inner padding of Fairy's dialog panel around the assistant content. */
internal val FairyDialogPaddingH = 6.dp
internal val FairyDialogPaddingV = 12.dp
internal val FairyDialogShape = RoundedCornerShape(14.dp)

private const val DIALOG_BREATH_MS = 1600
private const val DIALOG_FLOW_MS = 2600
private val DialogFillTop = Color(0xFF0B1631)
private val DialogFillBottom = Color(0xFF060C1E)
private val DialogBorder = Color(0xFFC9D5EE)
private val DialogBorderActive = Color(0xFF9CC2FF)

/**
 * Fairy's reply surface after the in-game dialog box (no avatar, no
 * continue marker): a deep-navy panel with a faint diagonal hatch and a top
 * inner highlight, framed by a thick light outline with a dark inner line.
 *
 * While the message is the working tail ([active]) the outline turns
 * glow-blue, a soft halo breathes around it and a bright highlight flows
 * along the edge. Animation values are read only while drawing; Reduced
 * Motion or the breath test seam holds a still frame.
 */
@Composable
internal fun Modifier.fairyDialogPanel(active: Boolean): Modifier {
    val tokens = LocalFairyTokens.current
    val override = LocalFairyBreathOverride.current
    val animate = active && override == null && LocalAgoraMotionPolicy.current.allowContinuousMotion
    val phases: Pair<State<Float>, State<Float>> = if (animate) {
        val transition = rememberInfiniteTransition(label = "fairyDialog")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(DIALOG_BREATH_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "fairyDialogBreath",
        ) to transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(DIALOG_FLOW_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "fairyDialogFlow",
        )
    } else {
        remember { mutableFloatStateOf(0.25f) to mutableFloatStateOf(0.1f) }
    }
    val density = LocalDensity.current
    val hatch = remember(density.density) {
        runCatching { hatchBrush(density.density, 7f, Color.White.copy(alpha = 0.022f)) }.getOrNull()
    }
    val glow = tokens.fairyGlow
    return drawWithCache {
        val outline = FairyDialogShape.createOutline(size, layoutDirection, this) as Outline.Rounded
        val rect = outline.roundRect
        val shape = Path().apply { addRoundRect(rect) }
        val border = 2.dp.toPx()
        val inner = Path().apply { addRoundRect(rect.inset(border)) }
        val fill = Brush.verticalGradient(
            0f to DialogFillTop.copy(alpha = 0.9f),
            1f to DialogFillBottom.copy(alpha = 0.92f),
        )
        val highlightHeight = 28.dp.toPx().coerceAtMost(size.height)
        val highlight = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.06f),
            1f to Color.Transparent,
            startY = 0f,
            endY = highlightHeight,
        )
        val center = Offset(size.width / 2f, size.height / 2f)
        onDrawBehind {
            drawPath(shape, fill)
            clipPath(shape) {
                if (hatch != null) drawRect(hatch)
                drawRect(highlight, size = Size(size.width, highlightHeight))
            }
            if (!active) {
                drawPath(shape, DialogBorder.copy(alpha = 0.3f), style = Stroke(border))
                drawPath(inner, Color.Black.copy(alpha = 0.4f), style = Stroke(1f))
                return@onDrawBehind
            }
            val b = override ?: breathAt(phases.first.value)
            // Breathing halo: two wide faint strokes outside the outline.
            drawPath(shape, glow.copy(alpha = 0.10f + 0.10f * b), style = Stroke(border * 5f))
            drawPath(shape, glow.copy(alpha = 0.16f + 0.14f * b), style = Stroke(border * 2.5f))
            drawPath(shape, lerp(DialogBorder, DialogBorderActive, 0.8f).copy(alpha = 0.75f + 0.2f * b), style = Stroke(border))
            drawPath(inner, Color.Black.copy(alpha = 0.4f), style = Stroke(1f))
            // Flowing highlight: a short bright arc of a sweep gradient.
            val p = phases.second.value
            drawPath(
                shape,
                brush = Brush.sweepGradient(*flowStops(p), center = center),
                style = Stroke(border * 1.5f),
            )
        }
    }
}

private fun RoundRect.inset(d: Float) = RoundRect(
    left = left + d,
    top = top + d,
    right = right - d,
    bottom = bottom - d,
    radiusX = (topLeftCornerRadius.x - d).coerceAtLeast(0f),
    radiusY = (topLeftCornerRadius.y - d).coerceAtLeast(0f),
)

/** Sweep stops with one bright arc centered on [p] (cycles), wrapping at 1. */
private fun flowStops(p: Float): Array<Pair<Float, Color>> {
    val bright = Color.White.copy(alpha = 0.85f)
    val clear = Color.White.copy(alpha = 0f)
    val half = 0.07f
    fun at(offset: Float): Float = ((p + offset) % 1f + 1f) % 1f
    val start = at(-half)
    val end = at(half)
    return if (start < end) {
        arrayOf(0f to clear, start to clear, at(0f) to bright, end to clear, 1f to clear)
    } else {
        // The arc straddles the 0/1 seam: fade in before 1, out after 0.
        val peak = at(0f)
        val edge = if (peak < end) {
            bright.copy(alpha = bright.alpha * (1f - peak / half).coerceIn(0f, 1f))
        } else {
            bright.copy(alpha = bright.alpha * ((peak - start) / half).coerceIn(0f, 1f))
        }
        if (peak < end) {
            arrayOf(0f to edge, peak to bright, end to clear, start to clear, 1f to edge)
        } else {
            arrayOf(0f to edge, end to clear, start to clear, peak to bright, 1f to edge)
        }
    }
}

/** A tile with one 1 px 45 degree line per [periodDp]; repeats seamlessly. */
private fun hatchBrush(densityScale: Float, periodDp: Float, color: Color): ShaderBrush {
    val tile = (periodDp * densityScale).roundToInt().coerceAtLeast(3)
    val bitmap = ImageBitmap(tile, tile)
    CanvasDrawScope().draw(
        density = Density(densityScale),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(tile.toFloat(), tile.toFloat()),
    ) {
        for (i in 0 until tile) drawRect(color, Offset(i.toFloat(), (tile - 1 - i).toFloat()), Size(1f, 1f))
    }
    return object : ShaderBrush() {
        override fun createShader(size: Size) =
            ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
    }
}
