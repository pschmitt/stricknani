package blue.anika.wolle.ui.home

import androidx.annotation.StringRes
import blue.anika.wolle.R

/** Time-of-day slots of the Home greeting. */
enum class GreetingSlot {
    MORNING,
    DAY,
    EVENING,
    NIGHT,
}

/** Every slot has exactly this many greetings (`home_greeting_<slot>_1..5`). */
const val GREETING_POOL_SIZE = 5

/** Morning 5-11, day 12-17, evening 18-22, night otherwise. */
fun greetingSlot(hour: Int): GreetingSlot =
    when (hour) {
        in 5..11 -> GreetingSlot.MORNING
        in 12..17 -> GreetingSlot.DAY
        in 18..22 -> GreetingSlot.EVENING
        else -> GreetingSlot.NIGHT
    }

private val pools: Map<GreetingSlot, List<Int>> =
    mapOf(
        GreetingSlot.MORNING to
            listOf(
                R.string.home_greeting_morning_1,
                R.string.home_greeting_morning_2,
                R.string.home_greeting_morning_3,
                R.string.home_greeting_morning_4,
                R.string.home_greeting_morning_5,
            ),
        GreetingSlot.DAY to
            listOf(
                R.string.home_greeting_day_1,
                R.string.home_greeting_day_2,
                R.string.home_greeting_day_3,
                R.string.home_greeting_day_4,
                R.string.home_greeting_day_5,
            ),
        GreetingSlot.EVENING to
            listOf(
                R.string.home_greeting_evening_1,
                R.string.home_greeting_evening_2,
                R.string.home_greeting_evening_3,
                R.string.home_greeting_evening_4,
                R.string.home_greeting_evening_5,
            ),
        GreetingSlot.NIGHT to
            listOf(
                R.string.home_greeting_night_1,
                R.string.home_greeting_night_2,
                R.string.home_greeting_night_3,
                R.string.home_greeting_night_4,
                R.string.home_greeting_night_5,
            ),
    )

/** The five greeting string resources of [slot]. */
fun greetingPool(slot: GreetingSlot): List<Int> = pools.getValue(slot)

/** The greeting string resource for [hour]; [index] is wrapped into the pool. */
@StringRes
fun greetingRes(hour: Int, index: Int): Int =
    greetingPool(greetingSlot(hour))[Math.floorMod(index, GREETING_POOL_SIZE)]
