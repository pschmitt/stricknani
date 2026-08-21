package blue.anika.wolle.data.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrConfigCodecTest {

    @Test
    fun `decodes a payload built exactly like the backend's qr_setup py`() {
        // Generated via `base64.urlsafe_b64encode(json.dumps(envelope).encode()).rstrip(b"=")` -
        // cross-checks against the actual Python encoder, not just this class's own round-trip.
        val uri =
            "stricknani://setup?p=eyJ2ZXJzaW9uIjogMSwgImNyZWF0ZWRBdCI6IDE3MDAwMDAwMDAwMDAsICJiYXNlVXJsIjogImh0dHBzOi8vc3RyaWNrbmFuaS5leGFtcGxlLmNvbSIsICJ0b2tlbiI6ICJzbmFfdGVzdDEyMyJ9"

        val decoded = QrConfigCodec.decode(uri)

        assertEquals(QrSetupPayload("https://stricknani.example.com", "sna_test123"), decoded)
    }

    @Test
    fun `looksLikeQrConfigUri matches only the stricknani setup prefix`() {
        assert(QrConfigCodec.looksLikeQrConfigUri("stricknani://setup?p=abc"))
        assert(!QrConfigCodec.looksLikeQrConfigUri("https://example.com/projects/1"))
        assert(!QrConfigCodec.looksLikeQrConfigUri("nyetbox://setup?p=abc"))
    }

    @Test
    fun `returns null for an unrelated URL`() {
        assertNull(QrConfigCodec.decode("https://stricknani.example.com/projects/1"))
    }

    @Test
    fun `returns null for garbage after the prefix`() {
        assertNull(QrConfigCodec.decode("stricknani://setup?p=not-valid-base64!!!"))
    }

    @Test
    fun `returns null for a well-formed but incomplete envelope`() {
        // Valid base64url JSON, but missing the required `token` field.
        val uri = "stricknani://setup?p=eyJiYXNlVXJsIjogImh0dHBzOi8vZXhhbXBsZS5jb20ifQ"
        assertNull(QrConfigCodec.decode(uri))
    }
}
