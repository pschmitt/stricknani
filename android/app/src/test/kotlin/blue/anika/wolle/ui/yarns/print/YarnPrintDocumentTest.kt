package blue.anika.wolle.ui.yarns.print

import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YarnPrintDocumentTest {

    private val labels =
        YarnPrintLabels(
            details = "Yarn details",
            brand = "Brand",
            colorway = "Colorway",
            dyeLot = "Dye lot",
            fiberContent = "Fiber content",
            weightCategory = "Weight category",
            recommendedNeedles = "Recommended needles",
            amount = "Amount",
            link = "Link",
            description = "Description",
            notes = "Notes",
            usedIn = "Used in",
        )

    private fun photo(id: Int, primary: Boolean) =
        YarnPhotoDto(
            id = id,
            url = "/media/yarns/3/$id.jpg",
            thumbnailUrl = "/media/thumbnails/yarns/3/$id.jpg",
            altText = "photo $id",
            isPrimary = primary,
        )

    private val yarn =
        YarnDto(
            id = 3,
            name = "Riverbend & Co Merino",
            brand = "Riverbend",
            colorway = "Cloud Blue",
            weightGrams = 100,
            lengthMeters = 225,
            description = "Soft **merino**.",
            notes = "Two skeins left.",
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = "2026-11-03T10:00:00Z",
            updatedAt = "2026-11-03T10:00:00Z",
            photos = listOf(photo(8, primary = false), photo(7, primary = true)),
        )

    private val resolve: (String?) -> String? = { path -> path?.let { "https://example.test$it" } }

    @Test
    fun `prints details, text and the projects using the yarn`() {
        val html =
            YarnPrintDocument.html(
                yarn,
                listOf("Heirloom Blanket"),
                false,
                labels,
                resolve,
                emptyMap(),
            )

        assertTrue(html.contains("<h1>Riverbend &amp; Co Merino</h1>"))
        assertTrue(html.contains("<dt>Amount</dt><dd>100 g / 225 m</dd>"))
        assertTrue(html.contains("<strong>merino</strong>"))
        assertTrue(html.contains("<li>Heirloom Blanket</li>"))
        assertTrue(html.contains("Two skeins left."))
        assertFalse(html.contains("Dye lot"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `photos are embedded primary first, and only when requested`() {
        val urls = YarnPrintDocument.imageUrls(yarn, includeImages = true, resolve = resolve)
        assertEquals(
            listOf(
                "https://example.test/media/yarns/3/7.jpg",
                "https://example.test/media/yarns/3/8.jpg",
            ),
            urls,
        )
        val embedded = urls.associateWith { "data:image/jpeg;base64,AAAA" }
        val html = YarnPrintDocument.html(yarn, emptyList(), true, labels, resolve, embedded)
        assertEquals(2, Regex("<img ").findAll(html).count())
        assertTrue(html.indexOf("photo 7") < html.indexOf("photo 8"))

        assertTrue(
            YarnPrintDocument.imageUrls(yarn, includeImages = false, resolve = resolve).isEmpty()
        )
    }
}
