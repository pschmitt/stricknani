package blue.anika.wolle.data.imports

import blue.anika.wolle.data.api.v2.ImportDraftRequest
import blue.anika.wolle.ui.common.ImportFailure
import blue.anika.wolle.ui.common.classifyImportError
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRequestsTest {
    // The app's Json (NetworkModule): defaults and nulls are left out.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun encode(request: ImportDraftRequest): JsonObject =
        json
            .parseToJsonElement(json.encodeToString(ImportDraftRequest.serializer(), request))
            .jsonObject

    @Test
    fun `a link request carries type, url and target`() {
        val body = encode(ImportDraftRequest(url = "https://x.y/p", useAi = true, target = "yarn"))

        assertEquals("url", body["type"]?.jsonPrimitive?.content)
        assertEquals("https://x.y/p", body["url"]?.jsonPrimitive?.content)
        assertEquals("yarn", body["target"]?.jsonPrimitive?.content)
        assertTrue(body["use_ai"]!!.jsonPrimitive.boolean)
        assertNull(body["text"])
    }

    @Test
    fun `a text request carries type text and the text, without a url`() {
        val body =
            encode(
                ImportDraftRequest(type = "text", text = "Cast on 48", useAi = false, locale = "de")
            )

        assertEquals("text", body["type"]?.jsonPrimitive?.content)
        assertEquals("Cast on 48", body["text"]?.jsonPrimitive?.content)
        assertEquals("de", body["locale"]?.jsonPrimitive?.content)
        assertNull(body["url"])
    }

    @Test
    fun `the file form has the file part and the use_ai flag`() {
        val file = ImportFile("pattern.pdf", "application/pdf", byteArrayOf(1, 2, 3))

        val (filePart, aiPart) = importFileParts(file, useAi = true)

        val fileHeader = filePart.headers!!["Content-Disposition"]!!
        assertTrue(fileHeader.contains("name=\"file\""))
        assertTrue(fileHeader.contains("filename=\"pattern.pdf\""))
        assertEquals("application/pdf", filePart.body.contentType().toString())
        assertEquals(3L, filePart.body.contentLength())
        assertTrue(aiPart.headers!!["Content-Disposition"]!!.contains("name=\"use_ai\""))
        assertEquals(4L, aiPart.body.contentLength()) // "true"
    }

    @Test
    fun `an unknown media type still makes a file part`() {
        val (filePart, _) = importFileParts(ImportFile("x", "", byteArrayOf(1)), useAi = false)

        assertNotNull(filePart.body)
        assertEquals(1L, filePart.body.contentLength())
    }

    @Test
    fun `reading stops at the limit`() {
        val data = ByteArray(10) { it.toByte() }

        assertArrayEquals(data, readImportBytes(ByteArrayInputStream(data), limit = 10))
        assertNull(readImportBytes(ByteArrayInputStream(data), limit = 9))
        assertArrayEquals(byteArrayOf(), readImportBytes(ByteArrayInputStream(byteArrayOf()), 1))
    }

    @Test
    fun `too large and unreadable documents are told apart`() {
        assertEquals(ImportFailure.TooLarge, classifyImportError(413, "payload_too_large"))
        assertEquals(ImportFailure.TooLarge, classifyImportError(413, null))
        assertEquals(ImportFailure.Unreadable, classifyImportError(422, "unsupported_document"))
        assertEquals(ImportFailure.Unreadable, classifyImportError(422, "invalid_document"))
        assertEquals(ImportFailure.InvalidUrl, classifyImportError(422, "validation_error"))
    }
}
