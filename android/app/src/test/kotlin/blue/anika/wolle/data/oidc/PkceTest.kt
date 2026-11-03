package blue.anika.wolle.data.oidc

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun `verifier is 43 to 128 characters of the unreserved set`() {
        repeat(50) {
            val verifier = Pkce.newCodeVerifier()
            assertTrue("length ${verifier.length}", verifier.length in 43..128)
            assertTrue(verifier, Regex("^[A-Za-z0-9._~-]+$").matches(verifier))
            assertTrue(Pkce.isValidVerifier(verifier))
        }
    }

    @Test
    fun `verifiers are not repeated`() {
        val seen = List(200) { Pkce.newCodeVerifier() }.toSet()
        assertEquals(200, seen.size)
    }

    @Test
    fun `challenge matches the RFC 7636 appendix B vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `challenge is unpadded base64url of a SHA-256 digest`() {
        val challenge = Pkce.codeChallenge(Pkce.newCodeVerifier())
        assertEquals(43, challenge.length)
        assertFalse(challenge.contains('='))
        assertFalse(challenge.contains('+'))
        assertFalse(challenge.contains('/'))
        assertEquals(32, Base64.getUrlDecoder().decode(challenge).size)
    }

    @Test
    fun `app state carries at least 128 bits and is fresh each time`() {
        val a = Pkce.newAppState()
        val b = Pkce.newAppState()
        assertNotEquals(a, b)
        assertTrue(Base64.getUrlDecoder().decode(a).size >= 16)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(a))
    }

    @Test
    fun `verifier validation rejects short long and foreign characters`() {
        assertFalse(Pkce.isValidVerifier("a".repeat(42)))
        assertFalse(Pkce.isValidVerifier("a".repeat(129)))
        assertFalse(Pkce.isValidVerifier("a".repeat(42) + "+"))
        assertTrue(Pkce.isValidVerifier("a".repeat(43)))
        assertTrue(Pkce.isValidVerifier("a".repeat(128)))
    }
}
