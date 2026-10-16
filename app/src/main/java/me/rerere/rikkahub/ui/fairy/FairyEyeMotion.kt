package me.rerere.rikkahub.ui.fairy

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Saccade time constant: the pupil lands on a new target in ~60 ms. */
private const val SACCADE_TAU_MS = 20f
/** Pursuit time constant while following a finger. */
private const val PURSUIT_TAU_MS = 45f
/** How long Fairy keeps looking at the spot a finger left. */
private const val RELEASE_HOLD_MS = 1500f
/** Farthest gaze, in eye units; past this the pupil slides under the Q ring. */
internal const val MAX_GAZE_UNITS = 2.2f
/** Small reading saccades around a held attention target. */
private const val ATTEND_JITTER_X = 0.3f
private const val ATTEND_JITTER_Y = 0.15f

private const val STARTLE_IN_MS = 80f
private const val STARTLE_HOLD_MS = 150f
private const val STARTLE_OUT_MS = 600f
private const val STARTLE_PUPIL = 0.6f
private const val STARTLE_SHAKE_MS = 300f
private const val STARTLE_SHAKE_UNITS = 0.35f

private const val BLINK_CLOSE_MS = 70f
private const val BLINK_SHUT_MS = 40f
private const val BLINK_OPEN_MS = 120f
private const val DOUBLE_BLINK_GAP_MS = 120f
private const val DOUBLE_BLINK_CHANCE = 0.15f
/** Upper lid position when drowsy (fraction of the iris covered). */
internal const val DROWSY_LID = 0.55f
private const val LID_EASE_MS = 800f
/** The lid lifts faster than it sinks (waking from a doze). */
private const val LID_LIFT_MS = 300f
/** Standby (asleep): the pupil widens a little and the gaze settles still. */
private const val SLEEP_PUPIL = 1.12f
private const val SLEEP_SETTLE_MS = 400f
private const val PUPIL_EASE_MS = 500f

/** "What happened?": a left-right look around after waking, then at the user. */
private val ConfusedScan = arrayOf(
    Offset(-1.9f, -0.2f) to 280f,
    Offset(1.9f, -0.2f) to 300f,
    Offset(-1.3f, 0f) to 240f,
    Offset(0f, 0.1f) to 900f,
)

/** One frame of the screen eye, in eye units (see [drawFairyEye]). */
@Immutable
internal data class FairyEyePose(
    val gaze: Offset = Offset.Zero,
    val pupil: Float = 1f,
    val lid: Float = 0f,
)

/** Test seam: when non-null, every [FairyScreen] eye holds this pose. */
internal val LocalFairyEyePoseOverride = staticCompositionLocalOf<FairyEyePose?> { null }

/** Lid at rest: drooping with [drowsy] (asleep, the eye stays open). */
internal fun restingLid(drowsy: Float): Float = DROWSY_LID * drowsy.coerceIn(0f, 1f)

/** Pupil at rest: wider when drowsy, a little wider still in standby. */
internal fun restingPupil(drowsy: Float, asleep: Boolean): Float = if (asleep) SLEEP_PUPIL else drowsyPupil(drowsy)

/** Pupil size for a drowsiness level: sleepy eyes dilate a little. */
internal fun drowsyPupil(drowsy: Float): Float = 1f + 0.15f * drowsy.coerceIn(0f, 1f)

/**
 * A host's handle on Fairy's attention and reactions, shared by every
 * [FairyScreen] it is passed to. Positions are root coordinates.
 *
 * - [lookAt]: a finger to pursue (null once lifted; the gaze lingers 1.5 s).
 * - [attend]: a held target such as the focused input box; Fairy keeps
 *   looking there, with small reading saccades, until it is cleared.
 * - [glanceAt]: a brief look, e.g. at a reply that just landed.
 * - [startle]: the pupil snaps small and recovers within a second.
 * - [blink], [wake] (a startle; [wake] with `confused` first looks left
 *   and right), [sleepNow] (straight into standby).
 * - [drowsy]: 0 awake .. 1 drowsy (lid at [DROWSY_LID], wider pupil), the
 *   brief doze; [asleep] is the standby after it: eye open, gaze still,
 *   pupil a little wide, slow blinks.
 *
 * Except [drowsy] and [asleep], values are plain fields polled by the screens' frame
 * loops, so writing them never recomposes anything.
 */
@Stable
class FairyEyeController(asleep: Boolean = false) {
    internal var pointer: Offset = Offset.Unspecified
        private set
    internal var attention: Offset = Offset.Unspecified
        private set
    internal var glance: Offset = Offset.Unspecified
        private set
    internal var glanceMs = 0f
        private set
    internal var glances = 0
        private set
    internal var startles = 0
        private set
    internal var blinks = 0
        private set
    internal var wakes = 0
        private set
    internal var wakeConfused = false
        private set
    internal var snaps = 0
        private set

    var drowsy by mutableFloatStateOf(0f)

    /** Standby: the eye stays open and still (see the class notes). */
    var asleep by mutableStateOf(asleep)

    fun lookAt(rootPosition: Offset?) {
        pointer = rootPosition ?: Offset.Unspecified
    }

    fun attend(rootPosition: Offset?) {
        attention = rootPosition ?: Offset.Unspecified
    }

    fun glanceAt(rootPosition: Offset, durationMs: Int) {
        glance = rootPosition
        glanceMs = durationMs.toFloat()
        glances++
    }

    fun startle() {
        startles++
    }

    fun blink() {
        blinks++
    }

    /** Opens the eyes at once (awake, drowsy 0) with a startle; [confused] looks around first. */
    fun wake(confused: Boolean = false) {
        asleep = false
        drowsy = 0f
        wakeConfused = confused
        wakes++
    }

    /** Standby right now, without the doze (e.g. found asleep on return). */
    fun sleepNow() {
        drowsy = 0f
        asleep = true
        snaps++
    }
}

/**
 * Per-frame input for [FairyEyeMotion.advance], refilled by [FairyScreen]
 * from its [FairyEyeController] (positions already in eye units, or
 * [Offset.Unspecified]) so frames allocate nothing.
 */
internal class FairyEyeInput {
    var pointer = Offset.Unspecified
    var attention = Offset.Unspecified
    var glance = Offset.Unspecified
    var glanceMs = 0f
    var glances = 0
    var startles = 0
    var blinks = 0
    var wakes = 0
    var wakeConfused = false
    var snaps = 0
    var drowsy = 0f
    var asleep = false
}

/**
 * Eye state machine advanced by [FairyScreen]'s frame loop. Gaze moves in
 * saccades between fixations (targets depend on [FairyPresence]) with a
 * small drift, or pursues a pointer, a glance or a held attention target;
 * the pupil eases toward a mood size and dips on startles; the upper lid
 * blinks every few seconds and droops with drowsiness. [seen] holds the
 * controller's event counters at creation so old events do not replay.
 */
internal class FairyEyeMotion(seed: Int, seen: FairyEyeController? = null) {
    private val random = Random(seed)
    private var gaze = Offset.Zero
    private var target = Offset.Zero
    private var fixationLeft = 600f
    private var tau = SACCADE_TAU_MS
    private var releaseHold = 0f
    private var glanceLeft = 0f
    private var attending = false
    private var attendLeft = 0f
    private var scriptIndex = -1
    private var scriptLeft = 0f
    private var thinkSide = 1f
    private var driftT = random.nextFloat() * 10_000f
    private var basePupil = restingPupil(seen?.drowsy ?: 0f, seen?.asleep == true)
    private var baseLid = restingLid(seen?.drowsy ?: 0f)
    private var driftScale = if (seen?.asleep == true) 0f else 1f
    private var seenStartles = seen?.startles ?: 0
    private var seenBlinks = seen?.blinks ?: 0
    private var seenWakes = seen?.wakes ?: 0
    private var seenGlances = seen?.glances ?: 0
    private var seenSnaps = seen?.snaps ?: 0

    /** Snaps from the controller this motion has applied (see [FairyScreen]). */
    val snapsSeen: Int get() = seenSnaps
    private var startleT = -1f
    private var blinkT = -1f
    private var nextBlink = 1500f + random.nextFloat() * 3000f
    private var pendingDouble = false

    /** Output for the current frame (eye units / multipliers). */
    var gazeX = 0f
        private set
    var gazeY = 0f
        private set
    var pupil = basePupil
        private set
    var lid = baseLid
        private set

    fun advance(dt: Float, presence: FairyPresence, input: FairyEyeInput) {
        val drowsy = input.drowsy.coerceIn(0f, 1f)
        if (input.snaps != seenSnaps) {
            seenSnaps = input.snaps
            baseLid = restingLid(drowsy)
            basePupil = restingPupil(drowsy, input.asleep)
            driftScale = if (input.asleep) 0f else 1f
            if (input.asleep) gaze = Offset.Zero
        }
        if (input.wakes != seenWakes) {
            seenWakes = input.wakes
            startleT = 0f
            blinkT = -1f
            if (input.wakeConfused) {
                scriptIndex = 0
                scriptLeft = ConfusedScan[0].second
            }
        }
        // Startle: restart the dip on every new event.
        if (input.startles != seenStartles) {
            seenStartles = input.startles
            startleT = 0f
        }
        if (input.blinks != seenBlinks) {
            seenBlinks = input.blinks
            if (blinkT < 0f) blinkT = 0f
        }
        if (input.glances != seenGlances) {
            seenGlances = input.glances
            glanceLeft = input.glanceMs
        }
        val shake = if (startleT >= 0f) {
            startleT += dt
            val t = startleT
            if (t > STARTLE_IN_MS + STARTLE_HOLD_MS + STARTLE_OUT_MS) startleT = -1f
            if (t < STARTLE_SHAKE_MS) {
                val a = STARTLE_SHAKE_UNITS * (1f - t / STARTLE_SHAKE_MS)
                Offset((random.nextFloat() * 2f - 1f) * a, (random.nextFloat() * 2f - 1f) * a)
            } else {
                Offset.Zero
            }
        } else {
            Offset.Zero
        }

        // Standby: the gaze settles at the center and holds still.
        val still = input.asleep && scriptIndex < 0
        if (still) {
            target = Offset.Zero
            tau = SLEEP_SETTLE_MS
        } else {
            advanceGaze(dt, presence, input, drowsy)
        }
        gaze += (target - gaze) * (1f - exp(-dt / tau))
        driftT += dt
        driftScale += ((if (still) 0f else 1f) - driftScale) * (1f - exp(-dt / SLEEP_SETTLE_MS))
        val drift = Offset(
            0.10f * sin(driftT / 1300f) + 0.05f * sin(driftT / 710f),
            0.08f * cos(driftT / 1100f),
        ) * driftScale

        // Pupil: mood size times the startle dip.
        val mood = when {
            input.asleep -> SLEEP_PUPIL
            drowsy > 0f -> drowsyPupil(drowsy)
            presence == FairyPresence.THINKING -> 1.08f
            else -> 1f
        }
        basePupil += (mood - basePupil) * (1f - exp(-dt / PUPIL_EASE_MS))
        pupil = basePupil * startleScale(startleT)

        // Lid: drowsy droop plus blinks (slow ones in standby); a startle snaps it open.
        val lidTarget = if (startleT >= 0f) 0f else restingLid(drowsy)
        val lidTau = when {
            startleT >= 0f -> 60f
            lidTarget < baseLid -> LID_LIFT_MS
            else -> LID_EASE_MS
        }
        baseLid += (lidTarget - baseLid) * (1f - exp(-dt / lidTau))
        val blink = advanceBlink(dt, if (input.asleep) 1f else drowsy)
        lid = baseLid + (1f - baseLid) * blink
        gazeX = gaze.x + drift.x + shake.x
        gazeY = gaze.y + drift.y + shake.y
    }

    /** Picks this frame's gaze target: script > finger > glance > attention > wander. */
    private fun advanceGaze(dt: Float, presence: FairyPresence, input: FairyEyeInput, drowsy: Float) {
        if (scriptIndex >= 0) {
            target = ConfusedScan[scriptIndex].first
            tau = SACCADE_TAU_MS
            scriptLeft -= dt
            if (scriptLeft <= 0f) {
                scriptIndex++
                if (scriptIndex >= ConfusedScan.size) {
                    scriptIndex = -1
                    fixationLeft = 0f
                } else {
                    scriptLeft = ConfusedScan[scriptIndex].second
                }
            }
            return
        }
        if (input.pointer.isSpecified) {
            target = input.pointer
            tau = PURSUIT_TAU_MS
            releaseHold = RELEASE_HOLD_MS
            return
        }
        if (releaseHold > 0f) {
            releaseHold -= dt
            if (releaseHold <= 0f) fixationLeft = 0f
            return
        }
        if (glanceLeft > 0f && input.glance.isSpecified) {
            target = input.glance
            tau = SACCADE_TAU_MS
            glanceLeft -= dt
            if (glanceLeft <= 0f) {
                fixationLeft = 0f
                attendLeft = 0f
            }
            return
        }
        // The focused input box holds her eye even while she works.
        if (input.attention.isSpecified) {
            if (!attending) attendLeft = 0f
            attending = true
            attendLeft -= dt
            if (attendLeft <= 0f) {
                // Reading: small hops around the target, never leaving it.
                target = input.attention + Offset(range(-1f, 1f) * ATTEND_JITTER_X, range(-1f, 1f) * ATTEND_JITTER_Y)
                tau = SACCADE_TAU_MS
                attendLeft = range(400f, 900f)
            }
            return
        }
        if (attending) {
            attending = false
            fixationLeft = 0f
        }
        fixationLeft -= dt
        if (fixationLeft <= 0f) pickFixation(presence, drowsy)
    }

    private fun pickFixation(presence: FairyPresence, drowsy: Float) {
        tau = SACCADE_TAU_MS
        val r = random.nextFloat()
        when {
            drowsy > 0.5f -> {
                // Heavy-eyed: settle low and stay there.
                target = Offset(range(-0.5f, 0.5f), range(0.6f, 1.1f))
                fixationLeft = range(3000f, 6000f)
            }
            presence == FairyPresence.THINKING -> {
                // Searching: up-left, up-right, now and then a glance back.
                target = if (r < 0.2f) {
                    Offset(range(-0.3f, 0.3f), range(-0.3f, 0.2f))
                } else {
                    thinkSide = -thinkSide
                    Offset(thinkSide * range(0.8f, 1.7f), -range(0.5f, 1.4f))
                }
                fixationLeft = range(350f, 900f)
            }
            presence == FairyPresence.SPEAKING -> {
                // Talking to the user: near center, a little low.
                target = Offset(range(-0.4f, 0.4f), range(0.4f, 0.9f))
                fixationLeft = range(700f, 1800f)
            }
            presence == FairyPresence.CONNECTING -> {
                thinkSide = -thinkSide
                target = Offset(thinkSide * range(1.2f, 1.6f), range(-0.2f, 0.2f))
                fixationLeft = range(600f, 900f)
            }
            else -> {
                // Idle: mostly rest near center, sometimes look around.
                target = if (r < 0.45f) {
                    Offset(range(-0.3f, 0.3f), range(-0.3f, 0.3f))
                } else {
                    val a = random.nextFloat() * 2f * PI.toFloat()
                    val d = range(0.8f, 1.8f)
                    Offset(d * cos(a), d * sin(a))
                }
                fixationLeft = range(1200f, 3400f)
            }
        }
    }

    /** Blink closure 0..1 for this frame; schedules the next blink. */
    private fun advanceBlink(dt: Float, drowsy: Float): Float {
        if (blinkT >= 0f) {
            blinkT += dt
            val closure = blinkClosure(blinkT)
            if (blinkT >= BLINK_CLOSE_MS + BLINK_SHUT_MS + BLINK_OPEN_MS) {
                blinkT = -1f
                if (pendingDouble) {
                    pendingDouble = false
                    nextBlink = DOUBLE_BLINK_GAP_MS
                }
            }
            return closure
        }
        // No blinks while startled: the eye is held wide open.
        if (startleT >= 0f) return 0f
        nextBlink -= dt
        if (nextBlink <= 0f) {
            blinkT = 0f
            val drowsyBlink = drowsy > 0.5f
            nextBlink = if (drowsyBlink) range(7000f, 12000f) else range(3000f, 7000f)
            if (!drowsyBlink && random.nextFloat() < DOUBLE_BLINK_CHANCE) pendingDouble = true
        }
        return 0f
    }

    private fun range(from: Float, to: Float) = from + (to - from) * random.nextFloat()
}

/**
 * Lid closure over one blink (t in ms since it started): the lid drops with
 * an accelerating ease, stays shut for a beat, then lifts and slows down.
 */
internal fun blinkClosure(t: Float): Float = when {
    t < 0f -> 0f
    t < BLINK_CLOSE_MS -> {
        val u = t / BLINK_CLOSE_MS
        u * u
    }
    t < BLINK_CLOSE_MS + BLINK_SHUT_MS -> 1f
    t < BLINK_CLOSE_MS + BLINK_SHUT_MS + BLINK_OPEN_MS -> {
        val u = (t - BLINK_CLOSE_MS - BLINK_SHUT_MS) / BLINK_OPEN_MS
        1f - u * (2f - u)
    }
    else -> 0f
}

/** Pupil multiplier over a startle (t in ms since the event, < 0 when none). */
internal fun startleScale(t: Float): Float = when {
    t < 0f -> 1f
    t < STARTLE_IN_MS -> {
        val u = t / STARTLE_IN_MS
        1f - (1f - STARTLE_PUPIL) * u * (2f - u)
    }
    t < STARTLE_IN_MS + STARTLE_HOLD_MS -> STARTLE_PUPIL
    t < STARTLE_IN_MS + STARTLE_HOLD_MS + STARTLE_OUT_MS -> {
        val u = (t - STARTLE_IN_MS - STARTLE_HOLD_MS) / STARTLE_OUT_MS
        STARTLE_PUPIL + (1f - STARTLE_PUPIL) * u * u * (3f - 2f * u)
    }
    else -> 1f
}

/**
 * Pointer direction from the eye center ([delta], px) as a gaze offset in
 * eye units: along the same direction, reaching [MAX_GAZE_UNITS] at
 * [reachPx] or farther.
 */
internal fun pointerGaze(delta: Offset, reachPx: Float): Offset {
    val d = sqrt(delta.x * delta.x + delta.y * delta.y)
    if (d < 1f) return Offset.Zero
    val k = MAX_GAZE_UNITS * (d / reachPx).coerceAtMost(1f) / d
    return Offset(delta.x * k, delta.y * k)
}
