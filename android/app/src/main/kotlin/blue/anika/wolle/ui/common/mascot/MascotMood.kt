package blue.anika.wolle.ui.common.mascot

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A lasting expression for the mascot, used by the error screens (the web twin has the same moods
 * on `Mascot.svelte`). [Happy] is the normal face; the others swap eyes and mouth and add a little
 * extra: a question mark, a loose thread, a tear, a few Zs or a sweat drop.
 */
enum class MascotMood(val periodMillis: Int) {
    Happy(0),
    Confused(3200),
    Dizzy(2800),
    Sad(3400),
    Sleepy(3600),
    Tired(3400),
}

/** The sideways tilt in degrees at loop position [phase] (`0..1`). */
fun moodTilt(mood: MascotMood, phase: Float): Float {
    val wave = sin(2f * PI.toFloat() * phase)
    return when (mood) {
        MascotMood.Confused -> 4f * wave
        MascotMood.Dizzy -> 6f * wave
        else -> 0f
    }
}

/** How far the ball sags (`0..1`) at loop position [phase]: a slow droop for sad and tired. */
fun moodDroop(mood: MascotMood, phase: Float): Float =
    when (mood) {
        MascotMood.Sad,
        MascotMood.Tired -> (1f - cos(2f * PI.toFloat() * phase)) / 2f
        else -> 0f
    }

/** The vertical breathing scale offset (about `-0.025..0.025`) of a sleepy ball. */
fun moodBreath(mood: MascotMood, phase: Float): Float =
    if (mood == MascotMood.Sleepy) 0.025f * sin(2f * PI.toFloat() * phase) else 0f
