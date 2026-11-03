package blue.anika.wolle.ui.oidc

import blue.anika.wolle.R
import blue.anika.wolle.data.oidc.OidcFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class OidcMessagesTest {
    @Test
    fun `unlinked account gets the neutral message`() {
        assertEquals(R.string.error_oidc_unlinked, OidcFailure.Unlinked.messageResId())
    }

    @Test
    fun `unavailable provider points to the password`() {
        assertEquals(R.string.error_oidc_unavailable, OidcFailure.Unavailable.messageResId())
    }

    @Test
    fun `rate limits mention the wait only when the server said how long`() {
        assertEquals(
            R.string.error_oidc_rate_limited_seconds,
            OidcFailure.RateLimited(30).messageResId(),
        )
        assertEquals(R.string.error_oidc_rate_limited, OidcFailure.RateLimited(null).messageResId())
    }

    @Test
    fun `stale links get the friendly expired message`() {
        assertEquals(R.string.error_oidc_expired, OidcFailure.Expired.messageResId())
    }

    @Test
    fun `every failure has a message`() {
        val all =
            listOf(
                OidcFailure.Expired,
                OidcFailure.Unlinked,
                OidcFailure.Unavailable,
                OidcFailure.RateLimited(1),
                OidcFailure.AlreadyLinked,
                OidcFailure.LastLoginMethod,
                OidcFailure.ReauthRequired,
                OidcFailure.InvalidPassword,
                OidcFailure.SessionExpired,
                OidcFailure.NotFound,
                OidcFailure.Validation,
                OidcFailure.ProviderError,
                OidcFailure.AccountMismatch,
                OidcFailure.Offline,
                OidcFailure.Other(500),
            )
        all.forEach { assertEquals(true, it.messageResId() != 0) }
    }

    @Test
    fun `linked date shows the day`() {
        assertEquals("2026-11-03", linkedDate("2026-11-03T12:00:00.000000Z"))
    }
}
