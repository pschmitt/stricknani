package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyPolicyTest {
    @Test
    fun usesTheConfiguredServer() {
        assertEquals("https://knit.example/privacy", privacyPolicyUrl("https://knit.example"))
        assertEquals("http://nas.lan:8000/privacy", privacyPolicyUrl(" http://nas.lan:8000/ "))
    }

    @Test
    fun fallsBackToThePublicPolicyWithoutAServer() {
        assertEquals(DEFAULT_PRIVACY_POLICY_URL, privacyPolicyUrl(""))
        assertEquals(DEFAULT_PRIVACY_POLICY_URL, privacyPolicyUrl("   "))
    }
}
