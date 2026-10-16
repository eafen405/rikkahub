package me.rerere.rikkahub.ui.fairy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Beveled panel edge: lit from above, fading toward the bottom. */
private val PanelEdge = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.18f),
    0.5f to Color.White.copy(alpha = 0.07f),
    1f to Color.White.copy(alpha = 0.04f),
)

/**
 * Fairy panel surface: [color] (default panel) inside [shape] with a 1 dp
 * beveled outline (brighter on top). Children are not clipped by this
 * modifier.
 */
@Composable
fun Modifier.fairyPanel(
    shape: Shape,
    color: Color? = null,
): Modifier {
    val tokens = LocalFairyTokens.current
    return this
        .background(color ?: tokens.panel, shape)
        .border(1.dp, PanelEdge, shape)
}

/**
 * HUD corner brackets: four L-shaped [arm]-long, [stroke]-thick marks at the
 * corners of the bounds, drawn over the content in [color] (default a faint
 * fairyLink).
 */
@Composable
fun Modifier.fairyHudCorners(
    color: Color? = null,
    arm: Dp = 9.dp,
    stroke: Dp = 1.5.dp,
): Modifier {
    val tint = color ?: LocalFairyTokens.current.fairyLink.copy(alpha = 0.45f)
    return drawWithContent {
        drawContent()
        val a = arm.toPx()
        val s = stroke.toPx()
        val w = size.width
        val h = size.height
        drawRect(tint, Offset(0f, 0f), Size(a, s))
        drawRect(tint, Offset(0f, 0f), Size(s, a))
        drawRect(tint, Offset(w - a, 0f), Size(a, s))
        drawRect(tint, Offset(w - s, 0f), Size(s, a))
        drawRect(tint, Offset(0f, h - s), Size(a, s))
        drawRect(tint, Offset(0f, h - a), Size(s, a))
        drawRect(tint, Offset(w - a, h - s), Size(a, s))
        drawRect(tint, Offset(w - s, h - a), Size(s, a))
    }
}
