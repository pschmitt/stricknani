package blue.anika.wolle.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GreetingPoolTest {
    @Test
    fun slotsFollowTheHour() {
        for (hour in 0..23) {
            val expected =
                when (hour) {
                    in 5..11 -> GreetingSlot.MORNING
                    in 12..17 -> GreetingSlot.DAY
                    in 18..22 -> GreetingSlot.EVENING
                    else -> GreetingSlot.NIGHT
                }
            assertEquals("hour $hour", expected, greetingSlot(hour))
        }
    }

    @Test
    fun everySlotHasFiveDistinctGreetings() {
        for (slot in GreetingSlot.values()) {
            val pool = greetingPool(slot)
            assertEquals(GREETING_POOL_SIZE, pool.size)
            assertEquals(GREETING_POOL_SIZE, pool.toSet().size)
        }
    }

    @Test
    fun poolsDoNotOverlapAcrossSlots() {
        val all = GreetingSlot.values().flatMap { greetingPool(it) }
        assertEquals(GreetingSlot.values().size * GREETING_POOL_SIZE, all.toSet().size)
    }

    @Test
    fun indexPicksWithinTheSlotPool() {
        for (index in 0 until GREETING_POOL_SIZE) {
            assertEquals(greetingPool(GreetingSlot.MORNING)[index], greetingRes(8, index))
            assertEquals(greetingPool(GreetingSlot.NIGHT)[index], greetingRes(2, index))
        }
        assertNotEquals(greetingRes(8, 0), greetingRes(13, 0))
    }

    @Test
    fun outOfRangeIndexWrapsInsteadOfCrashing() {
        assertEquals(greetingRes(13, 0), greetingRes(13, GREETING_POOL_SIZE))
        assertEquals(greetingRes(13, GREETING_POOL_SIZE - 1), greetingRes(13, -1))
    }
}
