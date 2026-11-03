package blue.anika.wolle.ui.common.mascot

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure tap-reaction logic of the yarn-ball mascot: the reaction sequence, the queue (at most one
 * pending), the tap-streak / easter-egg detector and the eye poses. No Compose, no clocks; the web
 * twin implements the same API shape.
 */
enum class MascotReaction(val durationMillis: Int) {
    Squish(600),
    WinkSwap(800),
    Spin(800),
    Hearts(800),
    Giggle(600),
}

/** Share of taps that continue the sequence; the rest pick a random other reaction. */
const val SEQUENCE_CHANCE = 0.7f

/**
 * The next reaction after [last]: 70% the one after it in the sequence, 30% a random other one. The
 * first ever reaction is always [MascotReaction.Squish].
 */
fun nextReaction(last: MascotReaction?, rand: () -> Float): MascotReaction {
    val all = MascotReaction.entries
    if (last == null) return all.first()
    val inSequence = all[(last.ordinal + 1) % all.size]
    if (rand() < SEQUENCE_CHANCE) return inSequence
    val others = all.filter { it != last && it != inSequence }
    return others[(rand().coerceIn(0f, 0.999999f) * others.size).toInt()]
}

/**
 * What plays and what waits. Rapid taps queue at most one extra reaction; anything beyond is
 * dropped. [request] returns the reaction to start now (or null), [finish] the queued one to start
 * next (or null).
 */
class ReactionQueue(private val rand: () -> Float) {
    var current: MascotReaction? = null
        private set

    var pending: MascotReaction? = null
        private set

    private var last: MascotReaction? = null

    fun request(): MascotReaction? {
        if (current == null) {
            current = nextReaction(last, rand).also { last = it }
            return current
        }
        if (pending == null) pending = nextReaction(last, rand).also { last = it }
        return null
    }

    fun finish(): MascotReaction? {
        current = pending
        pending = null
        return current
    }
}

const val TAP_STREAK_GAP_MILLIS = 450L
const val EASTER_EGG_TAPS = 7

/**
 * Counts quick taps (gap of at most [gapMillis] between two). Reaching [target] returns that count
 * once and starts over.
 */
class TapStreak(
    private val gapMillis: Long = TAP_STREAK_GAP_MILLIS,
    private val target: Int = EASTER_EGG_TAPS,
) {
    private var count = 0
    private var lastMillis = Long.MIN_VALUE

    /** Registers a tap at [nowMillis] and returns the length of the current streak. */
    fun tap(nowMillis: Long): Int {
        count =
            if (lastMillis != Long.MIN_VALUE && nowMillis - lastMillis <= gapMillis) count + 1
            else 1
        lastMillis = nowMillis
        val result = count
        if (count >= target) count = 0
        return result
    }

    fun isEasterEgg(streak: Int): Boolean = streak >= target
}

/** Taps in a streak from which each tap adds a bit of spin. */
const val SPIN_STREAK_TAPS = 3

/** One floating heart: [dx] is the start offset from the ball centre in 108-units. */
data class HeartParticle(
    val dx: Float,
    val delayFraction: Float,
    val size: Float,
    val drift: Float,
)

/** 3 to 5 tiny hearts spread over the top of the ball. */
fun heartParticles(rand: () -> Float): List<HeartParticle> {
    val count = 3 + (rand().coerceIn(0f, 0.999999f) * 3).toInt()
    return List(count) { index ->
        val slot = (index + 0.5f) / count
        HeartParticle(
            dx = (slot - 0.5f) * 30f + (rand() - 0.5f) * 4f,
            delayFraction = index * 0.12f,
            size = 2.6f + rand() * 1.6f,
            drift = (rand() - 0.5f) * 2f,
        )
    }
}

enum class EyeShape {
    Dot,
    Arc,
    Cross,
}

/** Left and right eye as drawn: by default an open left eye and a winking right one. */
data class Eyes(val left: EyeShape = EyeShape.Dot, val right: EyeShape = EyeShape.Arc)

/** The eyes for a reaction at progress [t] in `0..1`; dizzy (X X) wins over everything. */
fun eyePose(reaction: MascotReaction?, t: Float, dizzy: Boolean): Eyes =
    when {
        dizzy -> Eyes(EyeShape.Cross, EyeShape.Cross)
        reaction == MascotReaction.WinkSwap ->
            when {
                t < 0.15f -> Eyes()
                t < 0.4f -> Eyes(EyeShape.Arc, EyeShape.Arc)
                t < 0.85f -> Eyes(EyeShape.Arc, EyeShape.Dot)
                else -> Eyes()
            }
        else -> Eyes()
    }

/** How giggly the face is (smile wider, cheeks rosier), `0..1`. */
fun giggleAmount(reaction: MascotReaction?, t: Float): Float =
    when (reaction) {
        MascotReaction.Giggle -> sin(PI.toFloat() * t.coerceIn(0f, 1f))
        MascotReaction.Hearts -> 0.5f * sin(PI.toFloat() * t.coerceIn(0f, 1f))
        else -> 0f
    }

/** Duration of the easter-egg confetti flight. */
const val CONFETTI_SECONDS = 1.4f
