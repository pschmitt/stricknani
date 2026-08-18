package blue.anika.wolle.data.onboarding

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Decodes the `stricknani://setup?p=<payload>` QR the web Settings page renders (SNA-13) - mirrors
 * nyetbox's `QrConfigCodec` exactly (a plain prefix strip + base64url, no padding, over a JSON
 * envelope), just decode-only since generation happens server-side here
 * (`stricknani/utils/qr_setup.py`), not on-device.
 */
data class QrSetupPayload(val baseUrl: String, val token: String)

@Serializable
private data class QrSetupEnvelope(
    val version: Int = 1,
    val createdAt: Long = 0,
    val baseUrl: String,
    val token: String,
)

object QrConfigCodec {
    private const val URI_PREFIX = "stricknani://setup?p="
    private val json = Json { ignoreUnknownKeys = true }

    fun looksLikeQrConfigUri(text: String): Boolean = text.trim().startsWith(URI_PREFIX)

    /**
     * Returns null for anything that isn't a recognizable, well-formed `stricknani://setup`
     * payload.
     */
    fun decode(text: String): QrSetupPayload? {
        val trimmed = text.trim()
        val encoded = trimmed.removePrefix(URI_PREFIX)
        if (encoded == trimmed) return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            val envelope = json.decodeFromString<QrSetupEnvelope>(bytes.decodeToString())
            envelope
                .takeIf { it.baseUrl.isNotBlank() && it.token.isNotBlank() }
                ?.let { QrSetupPayload(it.baseUrl, it.token) }
        }
            .getOrNull()
    }
}
