package blue.anika.wolle.data.settings

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthInfoTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")

    @Test
    fun `an oidc token past its expiry is expired`() {
        assertTrue(AuthInfo("2026-11-10T11:59:59.000000Z", "oidc").isPastExpiry(now))
    }

    @Test
    fun `an oidc token before its expiry is not`() {
        assertFalse(AuthInfo("2026-11-10T12:00:01Z", "oidc").isPastExpiry(now))
    }

    @Test
    fun `password and pasted tokens never count as expired`() {
        assertFalse(AuthInfo("2020-01-01T00:00:00Z", null).isPastExpiry(now))
        assertFalse(AuthInfo(null, null).isPastExpiry(now))
    }

    @Test
    fun `a missing or unreadable expiry is not treated as expired`() {
        assertFalse(AuthInfo(null, "oidc").isPastExpiry(now))
        assertFalse(AuthInfo("garbage", "oidc").isPastExpiry(now))
    }
}
