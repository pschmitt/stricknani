package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.v2.ImportDraftRequest
import blue.anika.wolle.data.api.v2.ImportPreviewDto
import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.data.api.v2.toV1
import blue.anika.wolle.di.NetworkModule
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectImporterTest {
    private val v2Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `a v2 draft maps into the write model`() {
        val body =
            """{"data":{"draft":{"kind":"html","sha256":"x","sourceUrl":"https://example.com/p",
            "project":{"name":" Textured cardigan ","description":"Desc","category":"Sweaters",
            "yarn":"Wool","yarns":[],"needles":"4 mm","otherMaterials":"buttons",
            "stitchSample":"20 sts","gaugeWidth":20,"gaugeHeight":28,"fiberContent":null,
            "colorway":null,"weightCategory":null,
            "steps":[{"stepNumber":0,"title":null,"description":"Cast on","images":["https://e.com/s.jpg"]},
                     {"stepNumber":2,"title":"Body","description":null,"images":[]}],
            "imageUrls":["https://e.com/a.jpg"],
            "assets":[{"url":"https://e.com/22-chart.jpg","kind":"diagram","label":"Diagram for DROPS 273-22"},
                      {"url":"https://e.com/p.pdf","kind":"pdf"}],
            "link":null,"brand":null,"extras":{"k":1}},
            "image":null},"content_sha256":"x","ai_requested":true,"is_ai_enhanced":false,
            "ai_fallback":true}}"""
        val result =
            v2Json
                .decodeFromString<V2Envelope<ImportPreviewDto>>(body)
                .data
                .toV1()
                .toPreview("https://example.com/pattern")

        assertEquals("Textured cardigan", result.request.name)
        assertEquals("Sweaters", result.request.category)
        assertEquals("4 mm", result.request.needles)
        assertEquals("20 sts", result.request.stitchSample)
        assertEquals("buttons", result.request.otherMaterials)
        // The draft has no link of its own: the draft's source URL is used.
        assertEquals("https://example.com/p", result.request.link)
        assertEquals(listOf("https://e.com/a.jpg"), result.imageUrls)
        assertEquals(listOf("Step 1", "Body"), result.request.steps.map { it.title })
        assertEquals(listOf(1, 2), result.request.steps.map { it.stepNumber })
        assertEquals(listOf("https://e.com/s.jpg"), result.request.steps[0].imageUrls)
        assertTrue(result.aiFallback)
        assertTrue(!result.request.isAiEnhanced)
        // Diagrams and PDFs all start out selected as attachments and keep their kind and label.
        assertEquals(
            listOf("diagram", "pdf"),
            result.request.attachmentUrls.map { it.kind },
        )
        assertEquals("Diagram for DROPS 273-22", result.request.attachmentUrls[0].label)
        assertTrue(result.request.attachmentUrls[1].isPdf)
        // Deselecting one in the review step leaves only the chosen attachment.
        assertEquals(
            listOf("https://e.com/22-chart.jpg"),
            result.withAssets(listOf("https://e.com/22-chart.jpg")).request.attachmentUrls.map {
                it.url
            },
        )
    }

    @Test
    fun `a diagram keeps its kind in the write request`() {
        val body =
            NetworkModule.provideJson()
                .encodeToString(
                    blue.anika.wolle.data.api.dto.ImportAssetDto(url = "https://e.com/chart.jpg")
                )
        assertEquals("""{"url":"https://e.com/chart.jpg","kind":"diagram"}""", body)
    }

    @Test
    fun `the write request sends attachment urls with their snake case name`() {
        val body =
            NetworkModule.provideJson()
                .encodeToString(
                    blue.anika.wolle.data.api.dto.ProjectWriteRequest(
                        name = "x",
                        attachmentUrls =
                            listOf(
                                blue.anika.wolle.data.api.dto.ImportAssetDto(
                                    url = "https://e.com/p.pdf",
                                    kind = "pdf",
                                )
                            ),
                    )
                )
        assertTrue(
            body,
            body.contains(""""attachment_urls":[{"url":"https://e.com/p.pdf","kind":"pdf"}]"""),
        )
    }

    @Test
    // The app's own Json (no encodeDefaults): `type` must still be sent, or the server answers 422
    // and every link import shows "invalid link".
    fun `the request body is the documented json`() {
        assertEquals(
            """{"type":"url","url":"https://e.com","target":"project"}""",
            NetworkModule.provideJson().encodeToString(ImportDraftRequest(url = "https://e.com")),
        )
        assertEquals(
            """{"type":"url","url":"https://e.com","use_ai":true,"target":"yarn","locale":"de"}""",
            NetworkModule.provideJson()
                .encodeToString(
                    ImportDraftRequest(
                        url = "https://e.com",
                        useAi = true,
                        target = "yarn",
                        locale = "de",
                    )
                ),
        )
    }
}
