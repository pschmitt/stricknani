package blue.anika.wolle.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/** The address field's hint is a bare host, so a bare host must be accepted as https. */
class ServerAddressTest {
    @Test
    fun `a bare host gets https`() {
        assertEquals("https://wolle.example.com", withDefaultScheme(" wolle.example.com "))
    }

    @Test
    fun `an address with a scheme stays as typed`() {
        assertEquals("http://10.0.2.2:7674", withDefaultScheme("http://10.0.2.2:7674"))
        assertEquals("https://wolle.example.com/", withDefaultScheme("https://wolle.example.com/"))
    }

    @Test
    fun `an empty address stays empty so the missing-address error shows`() {
        assertEquals("", withDefaultScheme("  "))
    }
}
