package blue.anika.wolle.data.oidc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OidcCallbackTest {
    @Test
    fun `parses a login callback`() {
        val cb = OidcCallback.parse("stricknani://oidc-callback?code=abc123&state=st_1&flow=login")
        assertNotNull(cb)
        assertEquals("abc123", cb!!.code)
        assertEquals("st_1", cb.state)
        assertEquals(OidcFlowType.LOGIN, cb.flowType)
        assertNull(cb.error)
    }

    @Test
    fun `parses link and reauth flows`() {
        assertEquals(
            OidcFlowType.LINK,
            OidcCallback.parse("stricknani://oidc-callback?code=c&state=s&flow=link")!!.flowType,
        )
        assertEquals(
            OidcFlowType.REAUTH,
            OidcCallback.parse("stricknani://oidc-callback?code=c&state=s&flow=reauth")!!.flowType,
        )
    }

    @Test
    fun `parses an error callback`() {
        val cb = OidcCallback.parse("stricknani://oidc-callback?state=s&error=oidc_failed")!!
        assertEquals("oidc_failed", cb.error)
        assertNull(cb.code)
        assertNull(cb.flowType)
    }

    @Test
    fun `an unknown flow value has no flow type`() {
        assertNull(
            OidcCallback.parse("stricknani://oidc-callback?code=c&state=s&flow=x")!!.flowType
        )
    }

    @Test
    fun `percent encoded values are decoded`() {
        val cb =
            OidcCallback.parse("stricknani://oidc-callback?code=a%2Bb&state=s%20t&flow=login")!!
        assertEquals("a+b", cb.code)
        assertEquals("s t", cb.state)
    }

    @Test
    fun `the first value of a repeated parameter wins`() {
        val cb =
            OidcCallback.parse("stricknani://oidc-callback?state=a&state=b&code=1&flow=login")!!
        assertEquals("a", cb.state)
    }

    @Test
    fun `other URIs are not callbacks`() {
        assertNull(OidcCallback.parse(null))
        assertNull(OidcCallback.parse(""))
        assertNull(OidcCallback.parse("stricknani://setup?p=abc"))
        assertNull(OidcCallback.parse("https://oidc-callback/?code=c&state=s&flow=login"))
        assertNull(OidcCallback.parse("stricknani://other?code=c&state=s&flow=login"))
        assertNull(OidcCallback.parse("stricknani://oidc-callback/extra?code=c&state=s"))
        assertNull(OidcCallback.parse("stricknani://oidc-callback?code=c&state=s#frag"))
        assertNull(OidcCallback.parse("not a uri at all"))
    }

    @Test
    fun `a callback without parameters parses with nothing set`() {
        val cb = OidcCallback.parse("stricknani://oidc-callback")!!
        assertNull(cb.code)
        assertNull(cb.state)
    }

    @Test
    fun `the code is not printed`() {
        val cb =
            OidcCallback.parse("stricknani://oidc-callback?code=SECRETCODE&state=s&flow=login")!!
        assertFalse(cb.toString().contains("SECRETCODE"))
    }

    @Test
    fun `the redirect URI is the exact registered one`() {
        assertEquals("stricknani://oidc-callback", OIDC_REDIRECT_URI)
    }
}
