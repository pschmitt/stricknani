package blue.anika.wolle.ui.passkey

import blue.anika.wolle.data.passkey.PasskeyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PasskeyMessagesTest {
    private val everyFailure =
        listOf(
            PasskeyFailure.Cancelled,
            PasskeyFailure.NoCredential,
            PasskeyFailure.AlreadyRegistered,
            PasskeyFailure.Unsupported,
            PasskeyFailure.NotRecognized,
            PasskeyFailure.Expired,
            PasskeyFailure.ReauthRequired,
            PasskeyFailure.LimitReached,
            PasskeyFailure.NoPasskeys,
            PasskeyFailure.Unavailable,
            PasskeyFailure.Rejected,
            PasskeyFailure.RateLimited(5),
            PasskeyFailure.SessionExpired,
            PasskeyFailure.Offline,
            PasskeyFailure.Other(500),
        )

    @Test
    fun `every failure has a message`() {
        everyFailure.forEach { assertNotEquals(0, it.messageResId()) }
    }

    @Test
    fun `distinct failures have distinct messages except the shared confirmations`() {
        val ids = everyFailure.map { it.messageResId() }
        // Rejected and Other are different strings; nothing collapses by accident.
        assertEquals(ids.size, ids.toSet().size)
    }
}
