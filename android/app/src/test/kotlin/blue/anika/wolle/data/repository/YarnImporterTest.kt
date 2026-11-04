package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.v2.ImportPreviewDto
import blue.anika.wolle.data.api.v2.ImportPreviewRequest
import blue.anika.wolle.data.api.v2.ImportsV2Api
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.api.v2.MetaV2ImportsDto
import blue.anika.wolle.data.api.v2.MetaV2UserDto
import blue.anika.wolle.data.api.v2.V2Envelope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YarnImporterTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val body =
        """{"data":{"draft":{"kind":"html","sha256":"x","sourceUrl":"https://shop.example/air",
        "pageKind":"yarn",
        "project":{"name":"DROPS Air"},
        "yarn":{"name":" DROPS Air ","brand":"DROPS Design","colorway":null,"description":"Airy.",
          "fiberContent":"65% Alpaca, 28% Polyamide, 7% Wool","weightCategory":"Aran",
          "weightGrams":50,"lengthMeters":150,"recommendedNeedles":"5 mm",
          "gauge":"17 sts = 10 cm","care":" ","price":"4.50 EUR","link":null,
          "imageUrls":["https://e.com/1.jpg","https://e.com/2.jpg","https://e.com/3.jpg","https://e.com/4.jpg"]},
        "provenance":{"yarn.brand":"structured","yarn.care":"ai","yarn.gauge":"text"},
        "warnings":["looks_like_pattern_page"]},
        "content_sha256":"x","ai_requested":true,"is_ai_enhanced":true,"ai_fallback":false}}"""

    private fun capabilities(ai: Boolean) =
        ApiCapabilities(
            object : MetaV2Api {
                override suspend fun getMeta(): V2Envelope<MetaV2Dto> =
                    V2Envelope(
                        MetaV2Dto(
                            user = MetaV2UserDto(1, "a@b.c"),
                            imports = MetaV2ImportsDto(url = true, ai = ai),
                        )
                    )
            }
        ) {
            "s\nt"
        }

    @Test
    fun `yarn import asks for the yarn target and maps details, notes and photos`() = runTest {
        var seen: ImportPreviewRequest? = null
        val importer =
            ApiYarnImporter(
                object : ImportsV2Api {
                    override suspend fun previewImport(request: ImportPreviewRequest) =
                        json.decodeFromString<V2Envelope<ImportPreviewDto>>(body).also {
                            seen = request
                        }
                },
                capabilities(ai = true),
            )

        val result = importer.importFromUrl("https://shop.example/air")

        assertEquals(
            ImportPreviewRequest(
                type = "url",
                url = "https://shop.example/air",
                useAi = true,
                target = "yarn",
            ),
            seen,
        )
        assertEquals("DROPS Air", result.request.name)
        assertEquals("DROPS Design", result.request.brand)
        assertEquals(50, result.request.weightGrams)
        assertEquals(150, result.request.lengthMeters)
        assertEquals("5 mm", result.request.recommendedNeedles)
        assertEquals("https://shop.example/air", result.request.link)
        assertTrue(result.request.isAiEnhanced)
        // All photos are offered; the first three start out selected.
        assertEquals(4, result.imageUrls.size)
        assertEquals(result.imageUrls.take(3), result.request.imageUrls)
        assertEquals(
            listOf(
                YarnImportNote(YarnImportNoteKind.Gauge, "17 sts = 10 cm"),
                YarnImportNote(YarnImportNoteKind.Price, "4.50 EUR"),
            ),
            result.notes,
        )
        assertEquals(listOf("yarn.care"), result.aiFields)
        assertEquals(listOf("looks_like_pattern_page"), result.warnings)
    }

    @Test
    fun `without an AI provider the request does not ask for AI`() = runTest {
        var seen: ImportPreviewRequest? = null
        val importer =
            ApiYarnImporter(
                object : ImportsV2Api {
                    override suspend fun previewImport(request: ImportPreviewRequest) =
                        json.decodeFromString<V2Envelope<ImportPreviewDto>>(body).also {
                            seen = request
                        }
                },
                capabilities(ai = false),
            )
        importer.importFromUrl("https://shop.example/air")
        assertEquals(false, seen?.useAi)
    }

    @Test
    fun `selecting photos replaces the attached pictures`() {
        val preview =
            ImportedYarnPreview(
                sourceUrl = "u",
                request = blue.anika.wolle.data.api.dto.YarnWriteRequest(name = "A"),
                imageUrls = listOf("a", "b"),
                aiFallback = false,
            )
        assertEquals(listOf("b"), preview.withImages(listOf("b")).request.imageUrls)
    }
}
