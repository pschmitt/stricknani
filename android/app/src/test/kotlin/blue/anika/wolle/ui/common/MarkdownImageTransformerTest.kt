package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownImageTransformerTest {

    @Test
    fun `renderer adapter resolves relative media links before Coil`() {
        val transformer = MarkdownImageTransformer { link ->
            if (link == "/media/project/photo.jpg") {
                "https://stricknani.example/media/project/photo.jpg"
            } else {
                null
            }
        }

        assertEquals(
            "https://stricknani.example/media/project/photo.jpg",
            transformer.resolve("/media/project/photo.jpg"),
        )
    }

    @Test
    fun `renderer adapter returns null for unsupported links`() {
        val transformer = MarkdownImageTransformer { null }

        assertNull(transformer.resolve("javascript:alert(1)"))
    }
}
