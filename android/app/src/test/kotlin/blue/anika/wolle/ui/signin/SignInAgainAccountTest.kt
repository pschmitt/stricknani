package blue.anika.wolle.ui.signin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account isolation of "sign in again" (T121.5): it keeps the Room cache and the queued writes, so
 * it must only accept the account that owns them.
 */
class SignInAgainAccountTest {
    @Test
    fun `the same account keeps the queue, ignoring case and spaces`() {
        assertTrue(isSameAccountForSignInAgain("anna@example.org", "anna@example.org"))
        assertTrue(isSameAccountForSignInAgain("Anna@Example.org", " anna@example.ORG "))
    }

    @Test
    fun `another account never inherits the queued writes of the first`() {
        assertFalse(isSameAccountForSignInAgain("anna@example.org", "ben@example.org"))
    }

    @Test
    fun `an unknown saved account (QR setup) cannot be compared and is accepted`() {
        assertTrue(isSameAccountForSignInAgain(null, "ben@example.org"))
        assertTrue(isSameAccountForSignInAgain("  ", "ben@example.org"))
    }
}
