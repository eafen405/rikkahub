package me.rerere.rikkahub.ui.fairy

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan

private const val IDLE_BREATH_MS = 4200f
private const val WORKING_BREATH_MS = 1600f
private const val IDLE_SCAN_PERIOD_MS = 2400f
private const val SPEECH_PULSE_MS = 180
private const val SPEECH_PULSE_SCALE = 1.03f
/** One Fairy.gif loop: working gaze cycle and flash period. */
private const val WORKING_CYCLE_MS = 4200f
private const val IDLE_GAZE_MS = 9000f
private const val ENERGY_EASE_MS = 400f
private const val IDLE_ROLL_MS = 5200f
private const val WORKING_ROLL_MS = 2600f
/** Notched disc: one turn per 4 s while working, 10 s at rest, 20 s in standby. */
private const val WORKING_DISC_DEG_PER_MS = 360f / 4000f
private const val IDLE_DISC_DEG_PER_MS = 360f / 10_000f
private const val SLEEP_DISC_DEG_PER_MS = 360f / 20_000f
/** Finger distance at which the gaze reaches its limit. */
private val POINTER_REACH = 160.dp

/**
 * Test seam: when non-null, every Fairy breathing surface (window glow, accent
 * bar) draws this fixed breath value instead of running its frame loop.
 */
internal val LocalFairyBreathOverride = staticCompositionLocalOf<Float?> { null }

/** Breath value (0..1) for a phase in cycles; 0 at phase 0, 1 at half a cycle. */
internal fun breathAt(phase: Float): Float = ((1.0 - cos(2.0 * PI * phase)) / 2.0).toFloat()

/** Glow alpha range (min to peak) per presence; null means no glow. */
internal fun fairyGlowRange(presence: FairyPresence): ClosedFloatingPointRange<Float>? =
    when (presence) {
        FairyPresence.IDLE -> 0.30f..0.55f
        FairyPresence.CONNECTING -> 0.15f..0.275f
        FairyPresence.THINKING, FairyPresence.SPEAKING -> 0.30f..0.85f
        FairyPresence.OFFLINE -> null
    }

@androidx.compose.runtime.Immutable
data class FairyWindowState(
    val presence: FairyPresence,
    val expanded: Boolean = false,
    val speechPulse: Int = 0,
    val awaitingReply: Boolean = false,
    val eye: FairyEyeController? = null,
)

/**
 * Shared per-screen animation clock; values are read only while drawing.
 * [energy] eases 0 (idle) to 1 (working) and blends every speed and the
 * flash, so presence changes never snap the motion.
 */
@Stable
private class FairyScreenClock {
    var breathPhase by mutableFloatStateOf(0f)
    var scanPhase by mutableFloatStateOf(0f)
    var gazePhase by mutableFloatStateOf(0f)
    var eyeX by mutableFloatStateOf(0f)
    var eyeY by mutableFloatStateOf(0f)
    var eyePupil by mutableFloatStateOf(1f)
    var eyeLid by mutableFloatStateOf(0f)
    var discAngle by mutableFloatStateOf(0f)
    var flashPhase by mutableFloatStateOf(0f)
    var rollPhase by mutableFloatStateOf(0f)
    var energy by mutableFloatStateOf(0f)

    fun advance(dt: Float, presence: FairyPresence, eye: FairyEyeMotion, asleep: Boolean) {
        val working = presence.isWorking
        val ease = (dt / ENERGY_EASE_MS).coerceAtMost(1f)
        energy += ((if (working) 1f else 0f) - energy) * ease
        eyeX = eye.gazeX
        eyeY = eye.gazeY
        eyePupil = eye.pupil
        eyeLid = eye.lid
        val breathPeriod = if (working) WORKING_BREATH_MS else IDLE_BREATH_MS
        val scanPeriod = if (working) IDLE_SCAN_PERIOD_MS / 2f else IDLE_SCAN_PERIOD_MS
        breathPhase = (breathPhase + dt / breathPeriod) % 1f
        scanPhase = (scanPhase + dt / scanPeriod) % 1f
        gazePhase = (gazePhase + dt / lerp(IDLE_GAZE_MS, WORKING_CYCLE_MS, energy)) % 1f
        // The disc turns on its own: gaze, pupil and lid never hold it.
        val discSpeed = if (asleep) SLEEP_DISC_DEG_PER_MS else lerp(IDLE_DISC_DEG_PER_MS, WORKING_DISC_DEG_PER_MS, energy)
        discAngle = (discAngle + dt * discSpeed) % 360f
        flashPhase = (flashPhase + dt / WORKING_CYCLE_MS) % 1f
        rollPhase = (rollPhase + dt / lerp(IDLE_ROLL_MS, WORKING_ROLL_MS, energy)) % 1f
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Copies [eye] into this frame's input, converting root positions to eye units. */
private fun FairyEyeInput.fill(eye: FairyEyeController?, eyeRoot: FloatArray, reachPx: Float) {
    fun units(p: Offset): Offset =
        if (p.isSpecified && !eyeRoot[0].isNaN()) pointerGaze(Offset(p.x - eyeRoot[0], p.y - eyeRoot[1]), reachPx)
        else Offset.Unspecified
    if (eye == null) return
    pointer = units(eye.pointer)
    attention = units(eye.attention)
    glance = units(eye.glance)
    glanceMs = eye.glanceMs
    glances = eye.glances
    startles = eye.startles
    blinks = eye.blinks
    wakes = eye.wakes
    wakeConfused = eye.wakeConfused
    snaps = eye.snaps
    drowsy = eye.drowsy
    asleep = eye.asleep
}

/**
 * Fairy's CRT screen after Fairy.gif: a near-black radial base, a cloudy
 * fairyGlow aura with horizontal bleed, the eye (the pupil drifts on a
 * figure-eight gaze, the notched disc turns, the rings bloom), a pale flash
 * once per working cycle, drifting 1 px scanlines, a rolling bright band, a
 * vignette and a 1 dp fairyBlue inner outline, clipped to the screen shape.
 * With [hud] the screen also draws corner brackets, a fixed status word
 * (see [fairyHudStatus]) and a level meter.
 *
 * The eye is alive through [FairyEyeMotion]: saccades between fixations
 * that follow the presence (THINKING searches up left and right, SPEAKING
 * looks at the user, IDLE rests and glances around), blinks, a pupil that
 * dips on [FairyEyeController.startle] and widens with drowsiness, and a
 * gaze that pursues [FairyEyeController.lookAt]. SPEAKING also pulses the
 * eye on [speechPulse]; the disc turns faster while working.
 *
 * All phases advance in one frame loop that only writes draw-phase state, so
 * frames redraw without recomposing. The loop runs only while continuous
 * motion is allowed, the presence animates, and the host lifecycle is at
 * least STARTED; Reduced Motion draws the still frame (breath 0.5, centered
 * gaze, no flash or band, scanlines parked).
 */
@Composable
fun FairyScreen(
    presence: FairyPresence,
    eyeSize: Dp,
    modifier: Modifier = Modifier,
    speechPulse: Int = 0,
    hud: Boolean = false,
    eye: FairyEyeController? = null,
    hudStatus: String? = null,
) {
    val tokens = LocalFairyTokens.current
    val allowContinuous = LocalAgoraMotionPolicy.current.allowContinuousMotion
    val breathOverride = LocalFairyBreathOverride.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val clock = remember { FairyScreenClock() }
    val motion = remember { FairyEyeMotion(seed = System.identityHashCode(clock), seen = eye) }
    val input = remember { FairyEyeInput() }
    val lidPaths = remember { FairyEyeLid() }
    val poseOverride = LocalFairyEyePoseOverride.current
    // Eye center in root coordinates, for pointer pursuit.
    val eyeRoot = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val reachPx = with(LocalDensity.current) { POINTER_REACH.toPx() }
    val currentEye by rememberUpdatedState(eye)
    val running = allowContinuous && breathOverride == null &&
        presence != FairyPresence.OFFLINE && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val currentPresence by rememberUpdatedState(presence)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val dt = (now - last).coerceIn(0L, 100L).toFloat()
            last = now
            input.fill(currentEye, eyeRoot, reachPx)
            motion.advance(dt, currentPresence, input)
            clock.advance(dt, currentPresence, motion, input.asleep)
        }
    }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(speechPulse) {
        if (speechPulse == 0 || presence != FairyPresence.SPEAKING || !allowContinuous) return@LaunchedEffect
        pulse.snapTo(SPEECH_PULSE_SCALE)
        pulse.animateTo(1f, tween(SPEECH_PULSE_MS, easing = FastOutSlowInEasing))
    }
    val glowRange = fairyGlowRange(presence)
    val eyeAlpha = if (presence == FairyPresence.OFFLINE) 0.4f else 1f
    val scanlineColor = Color.White.copy(alpha = 0.06f)
    val stillEnergy = if (presence.isWorking) 1f else 0f
    val measurer = if (hud) rememberTextMeasurer() else null
    val hudWord = hudStatus ?: fairyHudStatus(presence)
    Spacer(
        modifier
            .then(
                if (eye != null) {
                    Modifier.onGloballyPositioned {
                        val p = it.positionInRoot()
                        eyeRoot[0] = p.x + it.size.width / 2f
                        eyeRoot[1] = p.y + it.size.height / 2f
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val outline = tokens.screenShape.createOutline(size, layoutDirection, this)
                val clip = Path().apply {
                    when (outline) {
                        is Outline.Rectangle -> addRect(outline.rect)
                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                        is Outline.Generic -> addPath(outline.path)
                    }
                }
                val center = Offset(size.width / 2f, size.height / 2f)
                val base = Brush.radialGradient(
                    0f to tokens.screenCenter,
                    0.55f to tokens.screenEdge,
                    1f to Color.Black,
                    center = center,
                    radius = maxOf(size.width, size.height) * 0.75f,
                )
                val eyePx = eyeSize.toPx()
                val unit = eyePx / 48f
                val disc = eyeDiscPath(unit, Offset.Zero)
                val eyeGlow = FairyEyeGlow(unit, tokens.fairyGlow)
                val paint = FairyScreenPaint(size, center, eyePx, tokens.fairyGlow)
                val hudLayout = measurer?.let {
                    FairyHudLayout(it, this, tokens.labelFontFamily, hudWord, tokens.fairyLink, tokens.textMuted)
                }
                val scanStep = 2.dp.toPx()
                val border = 1.dp.toPx()
                onDrawBehind {
                    clipPath(clip) {
                        drawRect(base)
                        val breath = breathOverride ?: if (running) breathAt(clock.breathPhase) else 0.5f
                        val energy = if (running) clock.energy else stillEnergy
                        val flash = if (running) fairyFlashAt(clock.flashPhase) * energy else 0f
                        if (glowRange != null) {
                            val a = glowRange.start + (glowRange.endInclusive - glowRange.start) * breath
                            drawFairyAura(paint, a, if (running) clock.gazePhase else 0f)
                            drawFairyFlash(paint, flash)
                        }
                        val drowsy = eye?.drowsy ?: 0f
                        val still = poseOverride ?: FairyEyePose(pupil = restingPupil(drowsy, eye?.asleep == true), lid = restingLid(drowsy))
                        // Until the loop applies a snap (e.g. found asleep on return), draw the still pose.
                        val synced = eye == null || motion.snapsSeen == eye.snaps
                        val live = running && poseOverride == null && synced
                        val gaze = if (live) Offset(clock.eyeX * unit, clock.eyeY * unit) else still.gaze * unit
                        val scale = pulse.value
                        withTransform({
                            translate(center.x, center.y)
                            scale(scale, scale, Offset.Zero)
                        }) {
                            drawFairyEye(
                                center = Offset.Zero,
                                unit = unit,
                                disc = disc,
                                alpha = eyeAlpha,
                                gaze = gaze,
                                discAngle = if (running) clock.discAngle else 0f,
                                glow = eyeGlow.takeIf { glowRange != null },
                                glowStrength = 0.45f + 0.35f * breath * (0.5f + 0.5f * energy) + 0.4f * flash,
                                pupil = if (live) clock.eyePupil else still.pupil,
                                lid = if (live) clock.eyeLid else still.lid,
                                lidPaths = lidPaths,
                            )
                        }
                        val phasePx = if (running) clock.scanPhase * scanStep else 0f
                        var y = phasePx - scanStep
                        while (y < size.height) {
                            if (y >= 0f) drawRect(scanlineColor, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 1f))
                            y += scanStep
                        }
                        drawFairyCrt(paint, if (running) clock.rollPhase else null)
                        if (hudLayout != null) {
                            drawFairyHud(hudLayout, if (running) clock.gazePhase * 3f % 1f else null, 0.25f + 0.75f * energy)
                        }
                        // Clipped double-width stroke: only the inner 1 dp shows.
                        drawPath(
                            clip,
                            color = tokens.fairyBlue.copy(alpha = 0.4f),
                            style = Stroke(width = border * 2f),
                        )
                    }
                }
            },
    )
}

/**
 * Slanted small-label look (~ -8 deg): a horizontal shear that leans the
 * content to the right, pivoting on the vertical center.
 */
fun Modifier.fairySlant(degrees: Float = 8f): Modifier = drawWithContent {
    val k = tan(Math.toRadians(degrees.toDouble())).toFloat()
    val matrix = Matrix().apply {
        values[Matrix.SkewX] = -k
        values[Matrix.TranslateX] = k * size.height / 2f
    }
    withTransform({ transform(matrix) }) {
        this@drawWithContent.drawContent()
    }
}
