package blue.anika.wolle.ui.common.mascot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MascotMoodTest {
    @Test
    fun `happy has no loop and every other mood has one`() {
        assertEquals(0, MascotMood.Happy.periodMillis)
        MascotMood.entries
            .filter { it != MascotMood.Happy }
            .forEach { assertTrue(it.name, it.periodMillis > 0) }
    }

    @Test
    fun `moods rest at the start of the loop`() {
        for (mood in MascotMood.entries) {
            assertEquals(0f, moodTilt(mood, 0f), 0.001f)
            assertEquals(0f, moodDroop(mood, 0f), 0.001f)
            assertEquals(0f, moodBreath(mood, 0f), 0.001f)
        }
    }

    @Test
    fun `only the matching moods move`() {
        assertTrue(moodTilt(MascotMood.Confused, 0.25f) > 0f)
        assertTrue(moodTilt(MascotMood.Dizzy, 0.75f) < 0f)
        assertEquals(0f, moodTilt(MascotMood.Sad, 0.25f), 0f)
        assertEquals(1f, moodDroop(MascotMood.Sad, 0.5f), 0.001f)
        assertEquals(1f, moodDroop(MascotMood.Tired, 0.5f), 0.001f)
        assertEquals(0f, moodDroop(MascotMood.Sleepy, 0.5f), 0f)
        assertTrue(moodBreath(MascotMood.Sleepy, 0.25f) > 0f)
        assertEquals(0f, moodBreath(MascotMood.Dizzy, 0.25f), 0f)
    }
}
