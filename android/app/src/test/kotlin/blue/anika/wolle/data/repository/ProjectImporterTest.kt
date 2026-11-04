package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ProjectImportApi
import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import blue.anika.wolle.data.api.dto.ProjectImportStepDto
import blue.anika.wolle.data.api.v2.ImportPreviewDto
import blue.anika.wolle.data.api.v2.ImportPreviewRequest
import blue.anika.wolle.data.api.v2.ImportsV2Api
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.api.v2.MetaV2UserDto
import blue.anika.wolle.data.api.v2.V2Envelope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private object NoV2 : ImportsV2Api {
    override suspend fun previewImport(
        request: ImportPreviewRequest
    ): V2Envelope<ImportPreviewDto> = error("v2 must not be called")
}

private fun v1Capabilities() =
    ApiCapabilities(
        object : MetaV2Api {
            override suspend fun getMeta(): V2Envelope<MetaV2Dto> = V2Envelope(MetaV2Dto())
        }
    ) {
        "s\nt"
    }

private fun v2Capabilities() =
    ApiCapabilities(
        object : MetaV2Api {
            override suspend fun getMeta(): V2Envelope<MetaV2Dto> =
                V2Envelope(MetaV2Dto(user = MetaV2UserDto(1, "a@b.c")))
        }
    ) {
        "s\nt"
    }

class ProjectImporterTest {
    @Test
    fun `maps web importer response into the offline project write model`() = runTest {
        val importer =
            ApiProjectImporter(
                object : ProjectImportApi {
                    override suspend fun importProject(
                        type: String,
                        url: String,
                        useAi: Boolean,
                    ) =
                        ProjectImportResponseDto(
                            title = "  Textured cardigan ",
                            category = "Sweaters",
                            needles = "4 mm",
                            description = "Description",
                            tags = listOf("winter", " "),
                            link = null,
                            imageUrls = listOf("https://example.com/photo.jpg"),
                            steps =
                                listOf(
                                    ProjectImportStepDto(
                                        stepNumber = null,
                                        title = null,
                                        description = "Cast on",
                                    )
                                ),
                        )
                },
                NoV2,
                v1Capabilities(),
            )

        val result = importer.importFromUrl("https://example.com/pattern")

        assertEquals("Textured cardigan", result.request.name)
        assertEquals("Sweaters", result.request.category)
        assertEquals("https://example.com/pattern", result.request.link)
        assertEquals(listOf("winter"), result.request.tags)
        assertEquals(1, result.request.steps.size)
        assertEquals("Step 1", result.request.steps.single().title)
        assertEquals(1, result.request.steps.single().stepNumber)
        assertEquals(listOf("https://example.com/photo.jpg"), result.imageUrls)
        assertTrue(!result.aiFallback)
    }

    private val v2Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `v2 preview posts json and maps the draft into the write model`() = runTest {
        var seen: ImportPreviewRequest? = null
        val body =
            """{"data":{"draft":{"kind":"html","sha256":"x","sourceUrl":"https://example.com/p",
            "project":{"name":" Textured cardigan ","description":"Desc","category":"Sweaters",
            "yarn":"Wool","yarns":[],"needles":"4 mm","otherMaterials":"buttons",
            "stitchSample":"20 sts","gaugeWidth":20,"gaugeHeight":28,"fiberContent":null,
            "colorway":null,"weightCategory":null,
            "steps":[{"stepNumber":0,"title":null,"description":"Cast on","images":["https://e.com/s.jpg"]},
                     {"stepNumber":2,"title":"Body","description":null,"images":[]}],
            "imageUrls":["https://e.com/a.jpg"],"link":null,"brand":null,"extras":{"k":1}},
            "image":null},"content_sha256":"x","ai_requested":true,"is_ai_enhanced":false,
            "ai_fallback":true}}"""
        val importer =
            ApiProjectImporter(
                object : ProjectImportApi {
                    override suspend fun importProject(type: String, url: String, useAi: Boolean) =
                        error("v1 must not be called")
                },
                object : ImportsV2Api {
                    override suspend fun previewImport(request: ImportPreviewRequest) =
                        v2Json.decodeFromString<V2Envelope<ImportPreviewDto>>(body).also {
                            seen = request
                        }
                },
                v2Capabilities(),
            )

        val result = importer.importFromUrl("https://example.com/pattern")

        assertEquals(ImportPreviewRequest(type = "url", url = "https://example.com/pattern"), seen)
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
    }

    @Test
    fun `the request body is the documented json`() {
        assertEquals(
            """{"type":"url","url":"https://e.com","use_ai":false,"target":"project"}""",
            Json { encodeDefaults = true }
                .encodeToString(ImportPreviewRequest(url = "https://e.com")),
        )
    }
}
