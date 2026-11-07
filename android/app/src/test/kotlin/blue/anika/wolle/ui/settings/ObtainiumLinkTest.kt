package blue.anika.wolle.ui.settings

import blue.anika.wolle.data.update.UpdateChannel
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObtainiumLinkTest {
    private fun payload(link: String): String {
        assertTrue(link.startsWith("obtainium://app/"))
        return URLDecoder.decode(link.removePrefix("obtainium://app/"), "UTF-8")
    }

    @Test
    fun `stable link has app fields and only the filter`() {
        val json = payload(ObtainiumLink.build(rolling = false))
        assertEquals(
            "{\"id\":\"blue.anika.wolle\",\"url\":\"https://github.com/pschmitt/stricknani\"," +
                "\"author\":\"pschmitt\",\"name\":\"Stricknani\"," +
                "\"additionalSettings\":\"{\\\"apkFilterRegEx\\\":\\\"-release\\\\\\\\.apk$\\\"}\"}",
            json,
        )
    }

    @Test
    fun `rolling link includes prereleases and date versioning`() {
        val json = payload(ObtainiumLink.build(rolling = true))
        assertTrue(json.contains("includePrereleases\\\":true"))
        assertTrue(json.contains("releaseDateAsVersion\\\":true"))
        assertFalse(payload(ObtainiumLink.build(false)).contains("includePrereleases"))
    }

    @Test
    fun `each update channel opens the link of its stream`() {
        assertEquals(
            ObtainiumLink.build(rolling = false),
            ObtainiumLink.build(UpdateChannel.STABLE),
        )
        assertEquals(ObtainiumLink.build(rolling = true), ObtainiumLink.build(UpdateChannel.LATEST))
    }

    @Test
    fun `link has no raw json characters or plus signs`() {
        val link = ObtainiumLink.build(rolling = true).removePrefix("obtainium://app/")
        assertFalse(link.any { it in "{}\" +\\" })
    }

    @Test
    fun `json strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", ObtainiumLink.jsonString("a\"b\\c\nd"))
        assertEquals("\"\\u0001\"", ObtainiumLink.jsonString("\u0001"))
    }

    @Test
    fun `filter regex matches release apks only`() {
        val re = Regex(ObtainiumLink.RELEASE_APK_FILTER)
        assertTrue(re.containsMatchIn("stricknani-v1.0.0-app-arm64-v8a-release.apk"))
        assertTrue(re.containsMatchIn("latest-app-x86-release.apk"))
        assertFalse(re.containsMatchIn("latest-app-arm64-v8a-debug.apk"))
        assertFalse(re.containsMatchIn("SHA256SUMS"))
    }
}
