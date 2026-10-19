package me.rerere.rikkahub.ui.fairy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * ZZZ-style slanted key: a parallelogram leaning right by [slant] (top edge
 * shifted right, bottom edge left) with [corner]-rounded vertices.
 */
class FairySlantShape(private val slant: Dp = 8.dp, private val corner: Dp = 7.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { slant.toPx() }
        val r = with(density) { corner.toPx() }
        val points = listOf(
            Offset(s, 0f),
            Offset(size.width, 0f),
            Offset(size.width - s, size.height),
            Offset(0f, size.height),
        )
        val path = Path()
        points.forEachIndexed { i, p ->
            val prev = points[(i + 3) % 4]
            val next = points[(i + 1) % 4]
            val a = p.towards(prev, r)
            val b = p.towards(next, r)
            if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
            path.quadraticTo(p.x, p.y, b.x, b.y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private fun Offset.towards(target: Offset, distance: Float): Offset {
        val d = target - this
        val length = d.getDistance()
        return if (length == 0f) this else this + d * (distance.coerceAtMost(length / 2f) / length)
    }
}
