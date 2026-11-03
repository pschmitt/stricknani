package blue.anika.wolle.data.oidc

import java.net.URI
import java.net.URLDecoder

/**
 * What the server's redirect to [OIDC_REDIRECT_URI] carried. The authorization [code] is
 * short-lived and single-use but still a credential: [toString] hides it.
 */
data class OidcCallback(
    val code: String?,
    val state: String?,
    /** The raw `flow` parameter; see [OidcFlowType.fromWire]. */
    val flow: String?,
    val error: String?,
) {
    val flowType: OidcFlowType?
        get() = OidcFlowType.fromWire(flow)

    override fun toString() = "OidcCallback(flow=$flow, error=$error)"

    companion object {
        /**
         * Parses `stricknani://oidc-callback?...`; returns null for any other URI (including the
         * `stricknani://setup` QR link, and look-alikes such as another host or a path or
         * fragment). Only the first value of each parameter is used.
         */
        fun parse(uri: String?): OidcCallback? {
            if (uri.isNullOrBlank()) return null
            val parsed = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
            if (!"stricknani".equals(parsed.scheme, ignoreCase = true)) return null
            if (!"oidc-callback".equals(parsed.host, ignoreCase = true)) return null
            if (!parsed.rawPath.isNullOrEmpty() && parsed.rawPath != "/") return null
            if (parsed.rawFragment != null) return null
            val params = parseQuery(parsed.rawQuery)
            return OidcCallback(
                code = params["code"]?.ifBlank { null },
                state = params["state"]?.ifBlank { null },
                flow = params["flow"]?.ifBlank { null },
                error = params["error"]?.ifBlank { null },
            )
        }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val key = decode(if (idx < 0) pair else pair.substring(0, idx))
                val value = if (idx < 0) "" else decode(pair.substring(idx + 1))
                result.putIfAbsent(key, value)
            }
            return result
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
