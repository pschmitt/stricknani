package blue.anika.wolle.data.passkey

import blue.anika.wolle.data.oidc.retrofitHttp
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasskeyFailureTest {
    @Test
    fun `server codes become the failure the screens explain`() {
        assertEquals(PasskeyFailure.NotRecognized, passkeyFailureFor(401, "invalid_passkey"))
        assertEquals(PasskeyFailure.Expired, passkeyFailureFor(400, "invalid_challenge"))
        assertEquals(PasskeyFailure.ReauthRequired, passkeyFailureFor(403, "reauth_required"))
        assertEquals(PasskeyFailure.LimitReached, passkeyFailureFor(409, "passkey_limit"))
        assertEquals(PasskeyFailure.NoPasskeys, passkeyFailureFor(409, "no_passkeys"))
        assertEquals(PasskeyFailure.Rejected, passkeyFailureFor(422, "passkey_rejected"))
        assertEquals(PasskeyFailure.Rejected, passkeyFailureFor(409, "passkey_exists"))
        assertEquals(PasskeyFailure.Unavailable, passkeyFailureFor(404, "passkeys_unavailable"))
        assertEquals(PasskeyFailure.SessionExpired, passkeyFailureFor(401, "session_expired"))
        assertEquals(PasskeyFailure.RateLimited(30), passkeyFailureFor(429, "rate_limited", 30))
        assertEquals(PasskeyFailure.Other(500), passkeyFailureFor(500, "internal_error"))
    }

    @Test
    fun `a server without the routes counts as passkeys unavailable`() {
        assertEquals(PasskeyFailure.Unavailable, passkeyFailureFor(404, null))
        assertEquals(PasskeyFailure.Unavailable, passkeyFailureFor(405, null))
    }

    @Test
    fun `thrown errors are classified through their causes`() {
        assertEquals(
            PasskeyFailure.NotRecognized,
            retrofitHttp(401, "invalid_passkey").toPasskeyFailure(),
        )
        assertEquals(
            PasskeyFailure.RateLimited(12),
            retrofitHttp(429, "rate_limited", "12").toPasskeyFailure(),
        )
        assertEquals(PasskeyFailure.Offline, IOException("down").toPasskeyFailure())
        assertEquals(
            PasskeyFailure.Offline,
            RuntimeException("wrapped", IOException("down")).toPasskeyFailure(),
        )
        assertEquals(
            PasskeyFailure.Cancelled,
            PasskeyException(PasskeyFailure.Cancelled).toPasskeyFailure(),
        )
        assertEquals(
            PasskeyFailure.Unavailable,
            PasskeyHttpException(404, null, null).toPasskeyFailure(),
        )
        assertEquals(PasskeyFailure.Other(null), IllegalStateException().toPasskeyFailure())
    }

    @Test
    fun `a successful result has no failure`() {
        assertNull(Result.success(1).passkeyFailure())
        assertEquals(
            PasskeyFailure.Offline,
            Result.failure<Int>(PasskeyException(PasskeyFailure.Offline)).passkeyFailure(),
        )
    }
}
