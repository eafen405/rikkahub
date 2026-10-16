package me.rerere.rikkahub.ui.fairy

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val BREATH_PERIOD_MS = 4200f
private const val SETTLE_MS = 300

private val EmblemRingTop = Color(0xFF3D8AE6)
private val EmblemRingBottom = Color(0xFF1A35D6)
private val EmblemRingTopBreath = Color(0xFF7FB8FF)
private val EmblemPupilTop = Color(0xFF0A1830)

/** Breathing cycle value: 0 at 0 ms, 0.5 at 1050 ms, 1 at 2100 ms, 0 at 4200 ms. */
fun emblemBreath(elapsedMs: Long): Float {
    val phase = (elapsedMs % BREATH_PERIOD_MS.toLong()) / BREATH_PERIOD_MS
    return ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()
}

/**
 * The flat Fairy emblem (see `res/drawable/fairy_emblem.xml` for the same
 * geometry): white ring, gradient blue ring, black disc with four compass
 * bumps, white eye ring, black pupil, white tail-dot.
 *
 * While [animating] and the motion policy allows continuous motion, the emblem
 * breathes on a 4200 ms cycle: the blue ring gradient lerps toward fairyGlow,
 * a radial glow halo behind the emblem fades to 0.55 alpha (radius 0.65 x
 * size, drawn beyond the bounds), and the eye scales 1.00 -> 1.04. Turning
 * [animating] off settles the breath back to 0 over 300 ms. Reduced Motion or
 * a non-animating state renders the emblem static — with no running
 * coroutines and no per-draw path/brush allocation.
 */
@Composable
fun FairyEmblem(
    animating: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val running = animating && motionPolicy.allowContinuousMotion

    val breath = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (running) {
            val start = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                breath.snapTo(emblemBreath(now - start))
            }
        } else {
            breath.animateTo(0f, tween(SETTLE_MS))
        }
    }

    FairyEmblemFrame(modifier = modifier, size = size, breath = { breath.value })
}

/** Test seam: draws one frame of the emblem at an explicit [breath] value. */
@Composable
internal fun FairyEmblem(
    animating: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    breathOverride: Float,
) {
    FairyEmblemFrame(modifier = modifier, size = size, breath = { breathOverride })
}

@Composable
private fun FairyEmblemFrame(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    breath: () -> Float,
) {
    val tokens = LocalFairyTokens.current
    val fairyGlow = tokens.fairyGlow
    Spacer(
        modifier.size(size).drawWithCache {
            val unit = this.size.minDimension / 48f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            // Disc+bumps path built once per size, not per frame.
            val disc = discPath(unit, center)
            val haloRadius = this.size.minDimension * 0.65f
            onDrawBehind {
                drawFairyEmblem(breath(), fairyGlow, unit, center, haloRadius, disc)
            }
        }
    )
}

private fun DrawScope.drawFairyEmblem(
    breath: Float,
    fairyGlow: Color,
    unit: Float,
    center: Offset,
    haloRadius: Float,
    disc: Path,
) {
    if (breath > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to fairyGlow.copy(alpha = 0.55f * breath),
                1f to fairyGlow.copy(alpha = 0f),
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = center,
        )
    }

    // White outer ring: r 24 -> 20.6 (centerline 22.3, width 3.4).
    drawCircle(
        color = Color.White,
        radius = 22.3f * unit,
        center = center,
        style = Stroke(width = 3.4f * unit),
    )
    // Blue ring r 20.6 -> 15.6 (centerline 18.1, width 5): vertical linear
    // gradient #3D8AE6 top -> #1A35D6 bottom; breathing brightens both stops
    // (#7FB8FF / fairyGlow).
    val ringR = 18.1f * unit
    drawCircle(
        brush = Brush.linearGradient(
            0f to lerp(EmblemRingTop, EmblemRingTopBreath, breath),
            1f to lerp(EmblemRingBottom, fairyGlow, breath),
            start = Offset(center.x, center.y - ringR),
            end = Offset(center.x, center.y + ringR),
        ),
        radius = ringR,
        center = center,
        style = Stroke(width = 5f * unit),
    )
    // Black disc r 15.6 with four right-angled triangular compass tips
    // (straight 45deg sides, tip r 19.0, base ~6.4 wide, ~0.4 rounded apex).
    drawPath(disc, Color.Black)
    // Eye: white ring outer r 12.5 (centerline 10.3, width 4.4), pupil r 8.1
    // (vertical gradient #0A1830 -> #000), tail-dot r 3.4 at r 6.1, 45deg
    // lower-right; the group breathes 1.00 -> 1.04.
    val eyeScale = 1f + 0.04f * breath
    drawCircle(
        color = Color.White,
        radius = 10.3f * unit * eyeScale,
        center = center,
        style = Stroke(width = 4.4f * unit * eyeScale, cap = StrokeCap.Round),
    )
    val pupilR = 8.1f * unit * eyeScale
    drawCircle(
        brush = Brush.verticalGradient(
            0f to EmblemPupilTop,
            1f to Color.Black,
            startY = center.y - pupilR,
            endY = center.y + pupilR,
        ),
        radius = pupilR,
        center = center,
    )
    val diagonal = 0.70710678f
    drawCircle(
        color = Color.White,
        radius = 3.4f * unit * eyeScale,
        center = Offset(
            center.x + 6.1f * unit * eyeScale * diagonal,
            center.y + 6.1f * unit * eyeScale * diagonal,
        ),
    )
}

/** Disc-with-compass-bumps path matching `fairy_emblem.xml`'s `disc` path. */
internal fun discPath(
    unit: Float,
    center: Offset,
    discRadius: Float = 15.6f,
    tipRadius: Float = 19f,
    halfBase: Float = 3.2f,
): Path {
    val r = discRadius * unit
    val tipR = tipRadius * unit
    val betaDeg = Math.toDegrees(kotlin.math.atan2(halfBase * unit, r).toDouble()).toFloat()
    fun pt(radius: Float, angleDeg: Float) = Offset(
        center.x + radius * cos(Math.toRadians(angleDeg.toDouble())).toFloat(),
        center.y + radius * sin(Math.toRadians(angleDeg.toDouble())).toFloat(),
    )
    val path = Path()
    val dirs = floatArrayOf(-90f, 0f, 90f, 180f)
    dirs.forEachIndexed { i, deg ->
        val b = pt(r, deg - betaDeg)
        val e = pt(r, deg + betaDeg)
        val t = pt(tipR, deg)
        if (i == 0) {
            path.moveTo(b.x, b.y)
        } else {
            path.arcTo(
                rect = Rect(center, r),
                startAngleDegrees = dirs[i - 1] + betaDeg,
                sweepAngleDegrees = deg - dirs[i - 1] - 2f * betaDeg,
                forceMoveTo = false,
            )
        }
        // Straight 45deg sides to the tip, with a ~0.4-unit rounded apex.
        val apex = 0.4f * unit
        val d1x = (t.x - b.x); val d1y = (t.y - b.y)
        val d1l = kotlin.math.hypot(d1x, d1y)
        val d2x = (e.x - t.x); val d2y = (e.y - t.y)
        val d2l = kotlin.math.hypot(d2x, d2y)
        val p1x = t.x - d1x / d1l * apex; val p1y = t.y - d1y / d1l * apex
        val p2x = t.x + d2x / d2l * apex; val p2y = t.y + d2y / d2l * apex
        path.lineTo(p1x, p1y)
        path.quadraticTo(t.x, t.y, p2x, p2y)
        path.lineTo(e.x, e.y)
    }
    path.arcTo(
        rect = Rect(center, r),
        startAngleDegrees = 180f + betaDeg,
        sweepAngleDegrees = 270f - 2f * betaDeg,
        forceMoveTo = false,
    )
    path.close()
    return path
}

private val EyeRingTop = Color(0xFF2A62CC)
private val EyeRingBottom = Color(0xFF12348F)
private val EyeRim = Color(0xFF7FAAF2)
private val EyeNotchDisc = Color(0xFF0B2A78)
private val EyeQRing = Color(0xFFEEF3FF)
private val EyeInnerRing = Color(0xFF8FB2F2)
private val EyePupilTop = Color(0xFF1846A6)
private val EyePupilBottom = Color(0xFF0A2A74)

/**
 * Ring-shaped bloom brushes for the screen eye, built once per eye size and
 * centered on the origin: a pale halo hugging the white Q ring and a blue
 * halo around the outer rim.
 */
internal class FairyEyeGlow(unit: Float, rimGlow: Color) {
    val qRingRadius = 22f * unit
    val qRing: Brush = Brush.radialGradient(
        0f to Color.Transparent,
        (5f / 22f) to Color.Transparent,
        (12.6f / 22f) to EyeQGlow.copy(alpha = 0.55f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = qRingRadius,
    )
    /**
     * The Q-ring bloom as it falls on a closed lid: the same light outside
     * the Q ring, fading out over the ring's area instead of peaking there.
     */
    val lid: Brush = Brush.radialGradient(
        0f to Color.Transparent,
        (4f / 22f) to Color.Transparent,
        (Q_RING_OUTER / 22f) to EyeQGlow.copy(alpha = 0.55f * (22f - Q_RING_OUTER) / (22f - 12.6f)),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = qRingRadius,
    )
    val rimRadius = 33f * unit
    val rim: Brush = Brush.radialGradient(
        0f to Color.Transparent,
        (19f / 33f) to Color.Transparent,
        (23.5f / 33f) to rimGlow.copy(alpha = 0.7f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = rimRadius,
    )
}

private val EyeQGlow = Color(0xFFC4DAFF)

/**
 * The CRT-screen eye: the emblem's concentric geometry in screen colors —
 * outer blue ring, dark disc with four notches, white Q ring, pale inner
 * ring, deep-blue pupil and the lower-right white dot. [disc] comes from
 * [discPath] with the eye radii (see [eyeDiscPath]) so frames allocate no
 * paths. Units are 1/48 of the eye diameter.
 *
 * Animation inputs: [gaze] shifts the inner ring and pupil (in px) while the
 * Q ring and tail dot stay put, [discAngle] turns the notched disc, and
 * [glowStrength] fades the [glow] ring blooms in. [pupil] works the Q ring
 * like an aperture: its outer edge stays, while its inner edge, the pupil,
 * the inner ring and the dot scale about the center. [lid] (0 open .. 1
 * shut) lowers the upper eyelid, a piece of the disc, over the iris through
 * [lidPaths]; the housing ring and the disc's notches stay visible around it.
 */
internal fun DrawScope.drawFairyEye(
    center: Offset,
    unit: Float,
    disc: Path,
    alpha: Float = 1f,
    gaze: Offset = Offset.Zero,
    discAngle: Float = 0f,
    glow: FairyEyeGlow? = null,
    glowStrength: Float = 0f,
    pupil: Float = 1f,
    lid: Float = 0f,
    lidPaths: FairyEyeLid? = null,
) {
    if (glow != null && glowStrength > 0f) {
        drawCircle(glow.rim, glow.rimRadius, center, alpha = alpha * glowStrength)
    }
    val ringR = 21.75f * unit
    drawCircle(
        brush = Brush.verticalGradient(
            0f to EyeRingTop,
            1f to EyeRingBottom,
            startY = center.y - 24f * unit,
            endY = center.y + 24f * unit,
        ),
        radius = ringR,
        center = center,
        style = Stroke(width = 4.5f * unit),
        alpha = alpha,
    )
    // Thin bright rim on the ring's outer edge.
    drawCircle(
        color = EyeRim,
        radius = 23.6f * unit,
        center = center,
        style = Stroke(width = 0.8f * unit),
        alpha = alpha * 0.8f,
    )
    if (discAngle == 0f) {
        drawPath(disc, EyeNotchDisc, alpha = alpha)
    } else {
        rotate(discAngle, pivot = center) { drawPath(disc, EyeNotchDisc, alpha = alpha) }
    }
    if (lidPaths == null || lid <= LID_EPSILON) {
        drawEyeIris(center, unit, alpha, gaze, glow, glowStrength, pupil)
        return
    }
    lidPaths.update(center, unit, lid)
    if (lid < 1f - LID_EPSILON) {
        clipPath(lidPaths.open) { drawEyeIris(center, unit, alpha, gaze, glow, glowStrength, pupil) }
    }
    // The lid is cut from the disc itself: same flat navy, so it reads as the
    // housing closing over the eye rather than a band sweeping across it.
    clipPath(lidPaths.closed) {
        drawPath(lidPaths.iris, EyeNotchDisc, alpha = alpha)
        // The Q-ring bloom lights the disc around the iris; light the lid the
        // same way so no seam shows where the lid meets the disc.
        if (glow != null && glowStrength > 0f) {
            drawCircle(glow.lid, glow.qRingRadius, center, alpha = alpha * glowStrength)
        }
    }
}

/** Pupil group, Q ring (as an aperture of [pupil]) and tail dot. */
private fun DrawScope.drawEyeIris(
    center: Offset,
    unit: Float,
    alpha: Float,
    gaze: Offset,
    glow: FairyEyeGlow?,
    glowStrength: Float,
    pupil: Float,
) {
    // Pupil group first so the gaze slides it under the white Q ring.
    val p = center + gaze
    val pupilR = 8.6f * unit * pupil
    drawCircle(
        brush = Brush.verticalGradient(
            0f to EyePupilTop,
            1f to EyePupilBottom,
            startY = p.y - pupilR,
            endY = p.y + pupilR,
        ),
        radius = pupilR,
        center = p,
        alpha = alpha,
    )
    drawCircle(
        color = EyeInnerRing,
        radius = 8f * unit * pupil,
        center = p,
        style = Stroke(width = 2.4f * unit * pupil),
        alpha = alpha,
    )
    if (glow != null && glowStrength > 0f) {
        drawCircle(glow.qRing, glow.qRingRadius, center, alpha = alpha * glowStrength)
    }
    // Aperture: the outer edge (15.4) is fixed, the inner edge (9.8) scales.
    val inner = Q_RING_INNER * pupil
    drawCircle(
        color = EyeQRing,
        radius = (Q_RING_OUTER + inner) / 2f * unit,
        center = center,
        style = Stroke(width = (Q_RING_OUTER - inner) * unit),
        alpha = alpha,
    )
    val angle = Math.toRadians(50.0)
    drawCircle(
        color = EyeQRing,
        radius = 3.2f * unit * pupil,
        center = Offset(
            center.x + 6.6f * unit * pupil * cos(angle).toFloat(),
            center.y + 6.6f * unit * pupil * sin(angle).toFloat(),
        ),
        alpha = alpha,
    )
}

private const val Q_RING_OUTER = 15.4f
private const val Q_RING_INNER = 9.8f
private const val LID_EPSILON = 0.002f

/**
 * Reusable eyelid paths for one eye (rebuilt in place each frame, never
 * reallocated). The lid is a disc of [LID_RADIUS] sliding down over the
 * iris, so its edge curves and a half-closed eye shows a crescent:
 * [closed] is that disc, [open] everything else, [iris] the disc body it
 * may cover. At 0 its lower edge sits just above the Q ring, at 1 the whole
 * iris is covered.
 */
internal class FairyEyeLid {
    val open = Path().apply { fillType = PathFillType.EvenOdd }
    val closed = Path()
    val iris = Path()

    fun update(center: Offset, unit: Float, lid: Float) {
        val visible = VISIBLE_RADIUS * unit
        val radius = LID_RADIUS * unit
        val bottom = center.y - visible + 2f * visible * lid
        val lidCenter = Offset(center.x, bottom - radius)
        val far = RADIUS * unit * 2f
        closed.reset()
        closed.addOval(Rect(lidCenter, radius))
        open.reset()
        open.addRect(Rect(center, far))
        open.addOval(Rect(lidCenter, radius))
        iris.reset()
        iris.addOval(Rect(center, RADIUS * unit))
    }

    companion object {
        /** Disc body radius the lid may cover, in eye units (see [eyeDiscPath]). */
        const val RADIUS = 19.5f
        /** Iris the lid travels across: the Q ring plus a hair. */
        private const val VISIBLE_RADIUS = 15.9f
        /** Lid disc radius: small enough to curve, large enough to shut the iris. */
        private const val LID_RADIUS = 18f
    }
}

/** The notched dark disc behind the eye's Q ring. */
internal fun eyeDiscPath(unit: Float, center: Offset): Path =
    discPath(unit, center, discRadius = 19.5f, tipRadius = 22.5f, halfBase = 3.6f)
