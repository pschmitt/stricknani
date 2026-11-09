package blue.anika.wolle.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmitEchoesTest {
    @Test
    fun olderEmittedTextsAreEchoes() {
        val echoes = EmitEchoes()
        listOf("H", "He", "Hel").forEach(echoes::record)
        assertTrue(echoes.isEcho("He"))
        assertFalse(echoes.isEcho("Restored draft"))
    }

    @Test
    fun clearForgetsEverything() {
        val echoes = EmitEchoes()
        echoes.record("a")
        echoes.clear()
        assertFalse(echoes.isEcho("a"))
    }

    @Test
    fun onlyTheLatestAreKept() {
        val echoes = EmitEchoes(limit = 2)
        listOf("1", "2", "3").forEach(echoes::record)
        assertFalse(echoes.isEcho("1"))
        assertTrue(echoes.isEcho("3"))
    }
}
