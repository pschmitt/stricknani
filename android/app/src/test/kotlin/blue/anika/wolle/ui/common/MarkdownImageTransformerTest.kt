package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownImageTransformerTest {

    @Test
    fun `renderer adapter resolves relative media links before Coil`() {
        val transformer = MarkdownImageTransformer(resolveUrl = { link ->
            if (link == "/media/project/photo.jpg") {
                "https://stricknani.example/media/project/photo.jpg"
            } else {
                null
            }
        })

        assertEquals(
            "https://stricknani.example/media/project/photo.jpg",
            transformer.resolve("/media/project/photo.jpg"),
        )
    }

    @Test
    fun `renderer adapter returns null for unsupported links`() {
        val transformer = MarkdownImageTransformer(resolveUrl = { null })

        assertNull(transformer.resolve("javascript:alert(1)"))
    }

    @Test
    fun `renderer adapter exposes image metadata for a source`() {
        val reference =
            MarkdownImageReference(
                source = "media/step.png",
                altText = "Sleeve seam",
                title = null,
                size = MarkdownImageSize.LG,
            )
        val transformer = MarkdownImageTransformer({ it }, imageReferences = listOf(reference))

        assertEquals(reference, transformer.referenceFor("media/step.png"))
    }
}
