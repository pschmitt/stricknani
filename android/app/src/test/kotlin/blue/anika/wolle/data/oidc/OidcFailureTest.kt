package blue.anika.wolle.data.oidc

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OidcFailureTest {
    private fun http(status: Int, code: String?, retryAfter: String? = null) =
        retrofitHttp(status, code, retryAfter)

    @Test
    fun `contract error table maps to client behaviour`() {
        assertEquals(OidcFailure.Unlinked, oidcFailureFor(403, "oidc_identity_unlinked"))
        assertEquals(OidcFailure.Unavailable, oidcFailureFor(503, "oidc_unavailable"))
        assertEquals(OidcFailure.Unavailable, oidcFailureFor(404, "oidc_disabled"))
        assertEquals(OidcFailure.AlreadyLinked, oidcFailureFor(409, "identity_already_linked"))
        assertEquals(OidcFailure.LastLoginMethod, oidcFailureFor(409, "last_login_method"))
        assertEquals(OidcFailure.Expired, oidcFailureFor(400, "invalid_oidc_flow"))
        assertEquals(OidcFailure.ReauthRequired, oidcFailureFor(403, "reauth_required"))
        assertEquals(OidcFailure.InvalidPassword, oidcFailureFor(401, "invalid_credentials"))
        assertEquals(OidcFailure.SessionExpired, oidcFailureFor(401, "session_expired"))
        assertEquals(OidcFailure.NotFound, oidcFailureFor(404, "not_found"))
        assertEquals(OidcFailure.Validation, oidcFailureFor(422, "validation_error"))
        assertEquals(OidcFailure.Other(500), oidcFailureFor(500, null))
    }

    @Test
    fun `rate limits carry Retry-After`() {
        assertEquals(OidcFailure.RateLimited(30), oidcFailureFor(429, "rate_limited", 30))
        assertEquals(OidcFailure.RateLimited(null), oidcFailureFor(429, null))
    }

    @Test
    fun `retry after is read as whole seconds`() {
        assertEquals(12L, parseRetryAfter("12"))
        assertEquals(12L, parseRetryAfter(" 12 "))
        assertEquals(0L, parseRetryAfter("0"))
        assertNull(parseRetryAfter("-1"))
        assertNull(parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(parseRetryAfter(null))
    }

    @Test
    fun `an http exception is classified by its body code`() {
        assertEquals(OidcFailure.Unlinked, http(403, "oidc_identity_unlinked").toOidcFailure())
        assertEquals(OidcFailure.LastLoginMethod, http(409, "last_login_method").toOidcFailure())
    }

    @Test
    fun `an http 429 exception reads the Retry-After header`() {
        assertEquals(
            OidcFailure.RateLimited(17),
            http(429, "rate_limited", retryAfter = "17").toOidcFailure(),
        )
    }

    @Test
    fun `an http exception without a body falls back to the status`() {
        assertEquals(OidcFailure.Unavailable, http(503, null).toOidcFailure())
        assertEquals(OidcFailure.Other(500), http(500, null).toOidcFailure())
    }

    @Test
    fun `io failures mean offline, also when wrapped`() {
        assertEquals(OidcFailure.Offline, IOException("x").toOidcFailure())
        assertEquals(OidcFailure.Offline, RuntimeException(IOException("x")).toOidcFailure())
    }

    @Test
    fun `wrapped failures and login client errors pass through`() {
        assertEquals(
            OidcFailure.AccountMismatch,
            OidcFailureException(OidcFailure.AccountMismatch).toOidcFailure(),
        )
        assertEquals(
            OidcFailure.RateLimited(5),
            OidcHttpException(429, null, 5).toOidcFailure(),
        )
    }

    @Test
    fun `unknown errors are generic`() {
        assertEquals(OidcFailure.Other(null), IllegalStateException("x").toOidcFailure())
    }

    @Test
    fun `error codes are read from a v2 body`() {
        assertEquals("session_expired", errorCodeOf("""{"error":{"code":"session_expired"}}"""))
        assertNull(errorCodeOf("not json"))
        assertNull(errorCodeOf("{}"))
    }
}
