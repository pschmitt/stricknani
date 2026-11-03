package blue.anika.wolle.ui.projects.print

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.StepDto
import blue.anika.wolle.ui.print.PrintContent
import blue.anika.wolle.ui.print.PrintOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPrintDocumentTest {

    private val labels =
        PrintLabels(
            details = "Details",
            category = "Category",
            needles = "Needles",
            yarn = "Yarn",
            otherMaterials = "Other materials",
            tags = "Tags",
            stitchSample = "Stitch sample",
            linkedYarns = "Linked yarns",
            description = "Description",
            steps = "Instructions",
            notes = "Notes",
        )

    private fun image(id: Int, stepId: Int? = null) =
        ImageDto(
            id = id,
            url = "/media/projects/1/$id.jpg",
            thumbnailUrl = "/media/thumbnails/projects/1/$id.jpg",
            altText = "image $id",
            imageType = "photo",
            isTitleImage = stepId == null,
            isStitchSample = false,
            stepId = stepId,
        )

    private val project =
        ProjectDto(
            id = 1,
            name = "Socks <for> Anna",
            category = "Socks",
            needles = "2.5 mm",
            description = "A **cosy** pair.",
            notes = "Use the *heel* from the other pattern.",
            steps =
                listOf(
                    StepDto(id = 12, title = "Heel", description = "Turn it.", stepNumber = 2),
                    StepDto(
                        id = 11,
                        title = "Cuff",
                        description = "Rib 2x2.\n\n![chart](/media/projects/1/9.jpg)",
                        stepNumber = 1,
                    ),
                ),
            images = listOf(image(5), image(6, stepId = 11)),
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = "2026-11-03T10:00:00Z",
            updatedAt = "2026-11-03T10:00:00Z",
        )

    private val resolve: (String?) -> String? = { path -> path?.let { "https://example.test$it" } }

    private fun embeddedFor(options: PrintOptions) =
        ProjectPrintDocument.imageUrls(project, options, resolve).associateWith {
            "data:image/jpeg;base64,AAAA"
        }

    @Test
    fun `everything prints details, text, steps in order and notes`() {
        val options = PrintOptions(PrintContent.EVERYTHING, includeImages = false)
        val html =
            ProjectPrintDocument.html(
                project,
                listOf("Merino"),
                options,
                labels,
                resolve,
                emptyMap(),
            )

        assertTrue(html.contains("<h1>Socks &lt;for&gt; Anna</h1>"))
        assertTrue(html.contains("<dt>Needles</dt><dd>2.5 mm</dd>"))
        assertTrue(html.contains("<strong>cosy</strong>"))
        assertTrue(html.contains("<li>Merino</li>"))
        assertTrue(html.contains("Notes"))
        assertTrue(html.indexOf("1. Cuff") < html.indexOf("2. Heel"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `instructions only leaves out details, description and notes`() {
        val options = PrintOptions(PrintContent.INSTRUCTIONS, includeImages = false)
        val html =
            ProjectPrintDocument.html(
                project,
                listOf("Merino"),
                options,
                labels,
                resolve,
                emptyMap(),
            )

        assertTrue(html.contains("1. Cuff"))
        assertFalse(html.contains("cosy"))
        assertFalse(html.contains("Merino"))
        assertFalse(html.contains("heel</em>"))
        assertFalse(html.contains("2.5 mm"))
    }

    @Test
    fun `images are embedded only when requested and downloaded`() {
        val options = PrintOptions(PrintContent.INSTRUCTIONS, includeImages = true)
        val urls = ProjectPrintDocument.imageUrls(project, options, resolve)
        assertEquals(
            setOf(
                "https://example.test/media/projects/1/6.jpg",
                "https://example.test/media/projects/1/9.jpg",
            ),
            urls.toSet(),
        )

        val html =
            ProjectPrintDocument.html(
                project,
                emptyList(),
                options,
                labels,
                resolve,
                embeddedFor(options),
            )
        assertEquals(2, Regex("<img ").findAll(html).count())
        assertFalse(html.contains("example.test"))

        val notDownloaded =
            ProjectPrintDocument.html(project, emptyList(), options, labels, resolve, emptyMap())
        assertFalse(notDownloaded.contains("<img"))
    }

    @Test
    fun `no image urls are requested without images`() {
        val options = PrintOptions(PrintContent.EVERYTHING, includeImages = false)
        assertTrue(ProjectPrintDocument.imageUrls(project, options, resolve).isEmpty())
    }
}
