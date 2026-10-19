package me.rerere.rikkahub.ui.fairy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import kotlin.math.tan

private const val WATERMARK = "HDD"
private const val WATERMARK_CAPTION = "HOLLOW DEEP DIVE SYSTEM"
private const val WATERMARK_SLANT_DEG = 10.0

/** Gradient, scanlines and optional watermark, sized to the containing Box. */
@Composable
fun BoxScope.FairyBackground(modifier: Modifier = Modifier, watermark: Boolean = true) {
    val tokens = LocalFairyTokens.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // ImageBitmap relies on Bitmap.createBitmap, null on software-only test
    // runtimes — fall back to the line loop there.
    val scanlines = remember(density.density, tokens.scanline) {
        runCatching { scanlineBrush(density.density, 3f, tokens.scanline) }.getOrNull()
    }
    Box(
        modifier
            .matchParentSize()
            .graphicsLayer()
            .drawWithCache {
                val gradient = Brush.verticalGradient(
                    0f to tokens.bgTop,
                    1f to tokens.bgBottom,
                )
                val glowCenter = Offset(size.width / 2f, 0f)
                val glowRadius = size.width * 0.9f
                val glow = Brush.radialGradient(
                    0f to tokens.ambientGlow,
                    1f to tokens.ambientGlow.copy(alpha = 0f),
                    center = glowCenter,
                    radius = glowRadius,
                )
                val mark = if (watermark) {
                    measurer.measure(
                        WATERMARK,
                        TextStyle(
                            fontFamily = tokens.titleFontFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = (size.width * 0.36f).toSp(),
                            color = tokens.fairyLink.copy(alpha = 0.035f),
                        ),
                    )
                } else {
                    null
                }
                val caption = if (watermark) {
                    measurer.measure(
                        WATERMARK_CAPTION,
                        TextStyle(
                            fontFamily = tokens.labelFontFamily,
                            fontSize = (size.width * 0.03f).toSp(),
                            letterSpacing = (size.width * 0.009f).toSp(),
                            color = tokens.fairyLink.copy(alpha = 0.06f),
                        ),
                    )
                } else {
                    null
                }
                val shear = tan(Math.toRadians(WATERMARK_SLANT_DEG)).toFloat()
                onDrawBehind {
                    drawRect(gradient)
                    if (mark != null && caption != null) {
                        // Anchored low on the page, right-aligned with a small margin.
                        val top = size.height * 0.62f
                        val left = size.width - mark.size.width - size.width * 0.03f
                        withTransform({
                            transform(Matrix().apply {
                                values[Matrix.SkewX] = -shear
                                values[Matrix.TranslateX] = shear * (top + mark.size.height / 2f)
                            })
                        }) {
                            drawText(mark, topLeft = Offset(left, top))
                            drawText(
                                caption,
                                topLeft = Offset(left + mark.size.width * 0.05f, top + mark.lastBaseline + caption.size.height * 0.4f),
                            )
                        }
                    }
                    drawScanlines(scanlines, tokens.scanline, 3f)
                    drawCircle(glow, radius = glowRadius, center = glowCenter)
                }
            },
    )
}

/** Draws 1 px scanlines every [periodDp], via [brush] when available. */
internal fun DrawScope.drawScanlines(
    brush: ShaderBrush?,
    color: Color,
    periodDp: Float,
    phasePx: Float = 0f,
) {
    if (brush != null && phasePx == 0f) {
        drawRect(brush = brush)
        return
    }
    val step = periodDp * density
    var y = (phasePx % step + step) % step
    while (y < size.height) {
        drawRect(color, topLeft = Offset(0f, y), size = Size(size.width, 1f))
        y += step
    }
}

/** A tile of one 1 px horizontal line per [periodDp]; repeats seamlessly. */
internal fun scanlineBrush(densityScale: Float, periodDp: Float, color: Color): ShaderBrush {
    val tile = (periodDp * densityScale).roundToInt().coerceAtLeast(2)
    val bitmap = ImageBitmap(1, tile)
    CanvasDrawScope().draw(
        density = Density(densityScale),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(1f, tile.toFloat()),
    ) {
        drawRect(color, topLeft = Offset.Zero, size = Size(1f, 1f))
    }
    return object : ShaderBrush() {
        override fun createShader(size: Size) =
            ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated)
    }
}
