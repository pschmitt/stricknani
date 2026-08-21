package blue.anika.wolle.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlResolverTest {

    @Test
    fun `resolves server relative paths below a configured path prefix`() {
        assertEquals(
            "https://stricknani.example/instance/media/project/photo.jpg",
            resolveMediaUrl(
                "https://stricknani.example/instance",
                "/media/project/photo.jpg",
            ),
        )
        assertEquals(
            "https://stricknani.example/instance/media/project/photo.jpg",
            resolveMediaUrl(
                "https://stricknani.example/instance",
                "media/project/photo.jpg",
            ),
        )
    }

    @Test
    fun `preserves query fragments and html entities for markdown destinations`() {
        assertEquals(
            "https://stricknani.example/media/photo.jpg?size=large&format=webp#preview",
            resolveMediaUrl(
                "https://stricknani.example",
                "media/photo.jpg?size=large&amp;format=webp#preview",
            ),
        )
    }

    @Test
    fun `keeps absolute image URLs stable for shared Coil cache keys`() {
        val resolved = resolveMediaUrl("https://stricknani.example", "/media/photo.jpg")

        assertEquals(
            resolved,
            resolveMediaUrl("https://stricknani.example", resolved),
        )
    }

    @Test
    fun `rejects unsafe and cross origin destinations with a safe fallback`() {
        assertNull(resolveMediaUrl("https://stricknani.example", "data:image/png;base64,abc"))
        assertNull(resolveMediaUrl("https://stricknani.example", "javascript:alert(1)"))
        assertNull(resolveMediaUrl("https://stricknani.example", "https://other.example/photo.jpg"))
        assertNull(resolveMediaUrl("https://stricknani.example", "//other.example/photo.jpg"))
        assertNull(
            resolveMediaUrl(
                "https://stricknani.example",
                "https://user:pass@evil.example/photo.jpg",
            )
        )
        assertNull(resolveMediaUrl("https://stricknani.example", "https://"))
    }
}
