package blue.anika.wolle.ui.common.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure physics and timing helpers for the yarn-ball mascot ("Winky Pom"). No Compose, no clocks and
 * no randomness of its own: callers inject `dt` and a `rand` source returning values in `[0, 1)`.
 * The web twin implements the same API shape, so keep the maths and the constants in sync.
 */
data class Vec2(val x: Float, val y: Float) {
    val length: Float
        get() = hypot(x, y)

    companion object {
        val Zero = Vec2(0f, 0f)
    }
}

/** Position and velocity of one spring-driven axis (dp, or degrees for the angle). */
data class Spring1(val pos: Float, val vel: Float)

/** The rubber-band limit while dragging the ball, in dp. */
const val DRAG_LIMIT_DP = 24f

/** Under-damped on purpose: damping ratio is about 0.36, so a release overshoots a few times. */
const val SPRING_STIFFNESS = 380f
const val SPRING_DAMPING = 14f

/** Friction constant of the spin: `omega *= exp(-k dt)`. */
const val SPIN_FRICTION = 1.6f

/** Below this angular speed (deg/s) the ball stops rolling and springs to an upright pose. */
const val SPIN_MIN_OMEGA = 180f

/** Above this angular speed (deg/s) the eyes go dizzy (X X). */
const val DIZZY_OMEGA = 360f

/** Fastest roll in deg/s. */
const val MAX_OMEGA = 2400f

/** Release speed (dp/s) below which a drag is a plain let-go and not a flick. */
const val FLICK_MIN_SPEED = 700f

const val FIDGET_MIN_MILLIS = 8000L
const val FIDGET_MAX_MILLIS = 15000L

/** Rubber-band: follows the pointer 1:1 near zero and approaches [limit] asymptotically. */
fun rubberBand(offset: Float, limit: Float): Float =
    if (limit <= 0f) 0f else limit * tanh(offset / limit)

/** Rubber-band of a 2D offset along its own direction. */
fun rubberBand(offset: Vec2, limit: Float): Vec2 {
    val len = offset.length
    if (len < 1e-6f) return Vec2.Zero
    val scale = rubberBand(len, limit) / len
    return Vec2(offset.x * scale, offset.y * scale)
}

/** One damped-spring step towards [target], sub-stepped so large frame times stay stable. */
fun springStep(
    state: Spring1,
    target: Float,
    dt: Float,
    stiffness: Float = SPRING_STIFFNESS,
    damping: Float = SPRING_DAMPING,
): Spring1 {
    val steps = (dt / MAX_SUBSTEP).toInt() + 1
    val h = dt / steps
    var pos = state.pos
    var vel = state.vel
    repeat(steps) {
        vel += (stiffness * (target - pos) - damping * vel) * h
        pos += vel * h
    }
    return Spring1(pos, vel)
}

private const val MAX_SUBSTEP = 1f / 120f

fun isSettled(state: Spring1, target: Float = 0f, posEps: Float = 0.05f, velEps: Float = 0.5f) =
    abs(state.pos - target) < posEps && abs(state.vel) < velEps

/** Whether a spin has died down to rest. */
fun isSettled(omega: Float, eps: Float = 20f): Boolean = abs(omega) < eps

/**
 * Release velocity (dp/s) to angular speed (deg/s): the horizontal direction picks the sense (right
 * is clockwise), the speed the magnitude. Slow releases give `0`.
 */
fun flickToOmega(vx: Float, vy: Float): Float {
    val speed = hypot(vx, vy)
    if (speed < FLICK_MIN_SPEED) return 0f
    val direction = if (abs(vx) >= abs(vy)) sign(vx) else sign(vy)
    return (direction * speed * 0.9f).coerceIn(-MAX_OMEGA, MAX_OMEGA)
}

/** Friction step of the spin. */
fun frictionStep(omega: Float, dt: Float, k: Float = SPIN_FRICTION): Float = omega * exp(-k * dt)

/** Adds a bit of spin (a quick tap streak or a second flick) and keeps it in range. */
fun addSpin(omega: Float, extra: Float): Float = (omega + extra).coerceIn(-MAX_OMEGA, MAX_OMEGA)

fun isDizzy(omega: Float): Boolean = abs(omega) > DIZZY_OMEGA

/** Unit-disc direction the face looks in for a drag offset; full strength at [reach]. */
fun lookDirection(dx: Float, dy: Float, reach: Float = DRAG_LIMIT_DP): Vec2 {
    if (reach <= 0f) return Vec2.Zero
    val len = hypot(dx, dy)
    if (len < 1e-6f) return Vec2.Zero
    val strength = (len / reach).coerceAtMost(1f)
    return Vec2(dx / len * strength, dy / len * strength)
}

/** `exp(-decay t) * sin(2 pi freq t)`: a decaying wobble that starts at zero. */
fun wobble(t: Float, freq: Float, decay: Float): Float =
    exp(-decay * t) * sin(2f * PI.toFloat() * freq * t)

/** Ease with a little overshoot (reaches 1.0 at `t = 1`). */
fun easeOutBack(t: Float, overshoot: Float = 1.4f): Float {
    val c = t.coerceIn(0f, 1f) - 1f
    return 1f + (overshoot + 1f) * c.pow(3) + overshoot * c.pow(2)
}

/** Idle fidgets of the mascot. */
enum class Fidget(val durationMillis: Int) {
    Blink(260),
    LookAround(1100),
    MicroBounce(480),
}

/** Delay until the next idle fidget, `8000..15000` ms. */
fun nextFidgetDelay(rand: () -> Float): Long =
    FIDGET_MIN_MILLIS +
        (rand().coerceIn(0f, 0.999999f) * (FIDGET_MAX_MILLIS - FIDGET_MIN_MILLIS)).toLong()

fun pickFidget(rand: () -> Float): Fidget {
    val all = Fidget.entries
    return all[(rand().coerceIn(0f, 0.999999f) * all.size).toInt()]
}
