package blue.anika.wolle.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusTest {
    @Test
    fun `a failure with working internet is a server problem, not offline`() {
        assertFalse(ioFailureIsOffline(hasValidatedInternet = true))
    }

    @Test
    fun `a failure without working internet is offline`() {
        assertTrue(ioFailureIsOffline(hasValidatedInternet = false))
    }

    @Test
    fun `an unknown network state keeps reporting offline`() {
        assertTrue(ioFailureIsOffline(hasValidatedInternet = null))
    }
}
