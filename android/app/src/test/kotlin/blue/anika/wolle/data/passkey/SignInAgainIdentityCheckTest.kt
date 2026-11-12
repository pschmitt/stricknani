package blue.anika.wolle.data.passkey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signing in again may only swap the token, never the account under the cache and the outbox. */
class SignInAgainIdentityCheckTest {
    private class Store(override var userId: Long?, override var email: String?) :
        AccountIdentityStore {
        override fun remember(userId: Long, email: String) {
            this.userId = userId
            this.email = email
        }
    }

    private val gateway = FakeGateway()
    private val login = FakeLoginApi()

    private fun signIn(store: Store, queued: Boolean) =
        PasskeySignInAgain(
            PasskeyRepository(
                FakePasskeyApi(),
                login,
                gateway,
                Json { ignoreUnknownKeys = true },
            ),
            SignInAgainIdentityCheck(login, store, { queued }),
        )

    /** The password path: a token was minted, the same check decides whether it may be saved. */
    private suspend fun password(store: Store, queued: Boolean) =
        SignInAgainIdentityCheck(login, store, { queued }).accepts("https://s", "pat-from-password")

    @Test
    fun `the account's own passkey is accepted even with queued work`() = runTest {
        login.identity = PasskeyIdentity(7, "me@example.org")
        val store = Store(7, "me@example.org")

        val outcome = signIn(store, queued = true).run(NoWindow, "https://s", "Pixel")

        assertEquals(SignInAgainOutcome.Accepted("pat-from-passkey"), outcome)
    }

    @Test
    fun `another account's passkey is rejected and its token is not handed out`() = runTest {
        login.identity = PasskeyIdentity(8, "other@example.org")
        val store = Store(7, "me@example.org")

        val outcome = signIn(store, queued = false).run(NoWindow, "https://s", "Pixel")

        assertEquals(SignInAgainOutcome.Mismatch, outcome)
        assertEquals("the remembered account is untouched", 7L, store.userId)
        assertEquals("me@example.org", store.email)
    }

    @Test
    fun `a known id wins over an email that happens to match`() = runTest {
        login.identity = PasskeyIdentity(8, "me@example.org")
        val outcome =
            signIn(Store(7, "me@example.org"), queued = false).run(NoWindow, "https://s", "P")
        assertEquals(SignInAgainOutcome.Mismatch, outcome)
    }

    @Test
    fun `an unknown identity with queued work is rejected`() = runTest {
        val store = Store(null, null)

        val outcome = signIn(store, queued = true).run(NoWindow, "https://s", "Pixel")

        assertEquals(SignInAgainOutcome.Mismatch, outcome)
        assertNull(store.userId)
    }

    @Test
    fun `an unknown identity with an empty queue is accepted and remembered`() = runTest {
        login.identity = PasskeyIdentity(9, "new@example.org")
        val store = Store(null, null)

        val outcome = signIn(store, queued = false).run(NoWindow, "https://s", "Pixel")

        assertEquals(SignInAgainOutcome.Accepted("pat-from-passkey"), outcome)
        assertEquals(9L, store.userId)
        assertEquals("new@example.org", store.email)
    }

    @Test
    fun `an older session is matched by its remembered email`() = runTest {
        login.identity = PasskeyIdentity(7, "Me@Example.org")
        val accepted =
            signIn(Store(null, "me@example.org"), queued = true).run(NoWindow, "https://s", "P")
        assertEquals(SignInAgainOutcome.Accepted("pat-from-passkey"), accepted)

        login.identity = PasskeyIdentity(8, "other@example.org")
        val refused =
            signIn(Store(null, "me@example.org"), queued = false).run(NoWindow, "https://s", "P")
        assertEquals(SignInAgainOutcome.Mismatch, refused)
    }

    @Test
    fun `a token whose owner cannot be told is rejected when an identity is known`() = runTest {
        login.identity = null
        assertEquals(
            SignInAgainOutcome.Mismatch,
            signIn(Store(7, null), queued = false).run(NoWindow, "https://s", "P"),
        )
        assertEquals(
            SignInAgainOutcome.Mismatch,
            signIn(Store(null, null), queued = true).run(NoWindow, "https://s", "P"),
        )
    }

    @Test
    fun `a dismissed sheet is a failure, not a mismatch`() = runTest {
        gateway.got = PasskeyOutcome.Failed(PasskeyFailure.Cancelled)
        val outcome = signIn(Store(7, null), queued = true).run(NoWindow, "https://s", "P")
        assertEquals(SignInAgainOutcome.Failed(PasskeyFailure.Cancelled), outcome)
    }

    // ---- password path: the same rule through the same helper ----------------------------------

    @Test
    fun `password - the account's own token is accepted even with queued work`() = runTest {
        login.identity = PasskeyIdentity(7, "me@example.org")
        assertTrue(password(Store(7, "me@example.org"), queued = true))
    }

    @Test
    fun `password - another account's token is rejected and the identity stays`() = runTest {
        login.identity = PasskeyIdentity(8, "other@example.org")
        val store = Store(7, "me@example.org")
        assertFalse(password(store, queued = false))
        assertEquals(7L, store.userId)
        assertEquals("me@example.org", store.email)
    }

    @Test
    fun `password - a known id wins over a matching email`() = runTest {
        login.identity = PasskeyIdentity(8, "me@example.org")
        assertFalse(password(Store(7, "me@example.org"), queued = false))
    }

    @Test
    fun `password - unknown identity with queued work is rejected`() = runTest {
        val store = Store(null, null)
        assertFalse(password(store, queued = true))
        assertNull(store.userId)
    }

    @Test
    fun `password - unknown identity with an empty queue is accepted and remembered`() = runTest {
        login.identity = PasskeyIdentity(9, "new@example.org")
        val store = Store(null, null)
        assertTrue(password(store, queued = false))
        assertEquals(9L, store.userId)
        assertEquals("new@example.org", store.email)
    }

    @Test
    fun `password - an older session is matched by email`() = runTest {
        login.identity = PasskeyIdentity(7, "Me@Example.org")
        assertTrue(password(Store(null, "me@example.org"), queued = true))
        login.identity = PasskeyIdentity(8, "other@example.org")
        assertFalse(password(Store(null, "me@example.org"), queued = false))
    }

    @Test
    fun `password - a token whose owner cannot be told is rejected`() = runTest {
        login.identity = null
        assertFalse(password(Store(7, null), queued = false))
        assertFalse(password(Store(null, null), queued = true))
    }
}
