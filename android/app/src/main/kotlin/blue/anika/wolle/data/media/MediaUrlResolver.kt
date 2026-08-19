package blue.anika.wolle.data.media

import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves a media path from a DTO (`/media/...`, relative - see `di/NetworkModule.kt`'s
 * `MediaClient` kdoc for why) into an absolute URL Coil can load, using the currently configured
 * server. The same resolver is also used for Markdown image destinations so both paths share Coil
 * cache keys and work offline after an image has been loaded once.
 */
@Singleton
class MediaUrlResolver @Inject constructor(private val settingsRepository: SettingsRepository) {
    fun resolve(path: String?): String? = resolveMediaUrl(settingsRepository.credentials.value.serverUrl, path)
}

private const val MAX_MEDIA_URL_LENGTH = 2_048

/**
 * Resolves a server media path or Markdown image destination without allowing unsafe schemes or
 * cross-origin destinations. Relative paths are resolved inside the configured server URL,
 * including when the server is hosted below a path prefix.
 */
fun resolveMediaUrl(serverUrl: String, path: String?): String? {
    val candidate = path?.let(::decodeHtmlEntities)?.trim() ?: return null
    if (candidate.isEmpty() || candidate.length > MAX_MEDIA_URL_LENGTH) return null

    val base = serverUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
    if (!base.isHttpOrHttps() || base.username.isNotEmpty() || base.password.isNotEmpty()) {
        return null
    }

    val absolute = candidate.toHttpUrlOrNull()
    if (absolute != null && absolute.isHttpOrHttps()) {
        return absolute
            .takeIf {
                it.username.isEmpty() && it.password.isEmpty() && it.sameOriginAs(base)
            }
            ?.toString()
    }
    if (URI_SCHEME.containsMatchIn(candidate)) return null

    // A protocol-relative destination can silently move image loading to another origin. Allow it
    // only when it still points at the configured server, matching the safety policy for relative
    // Markdown destinations.
    if (candidate.startsWith("//")) {
        val protocolRelative = (base.scheme + ":" + candidate).toHttpUrlOrNull() ?: return null
        return protocolRelative
            .takeIf {
                it.isHttpOrHttps() &&
                    it.username.isEmpty() &&
                    it.password.isEmpty() &&
                    it.sameOriginAs(base)
            }
            ?.toString()
    }

    // Stricknani API media paths are server-relative, not domain-root-relative. Stripping the
    // leading slash keeps a configured path prefix (for example /stricknani/media/...); HttpUrl
    // still normalizes dot segments and query/fragment components for ordinary relative links.
    val relative = candidate.trimStart('/')
    if (relative.isEmpty()) return null
    val resolved = (base.toString().trimEnd('/') + "/" + relative).toHttpUrlOrNull() ?: return null
    return resolved.takeIf { it.isHttpOrHttps() }?.toString()
}

private fun HttpUrl.isHttpOrHttps(): Boolean = scheme == "http" || scheme == "https"

private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

private fun decodeHtmlEntities(value: String): String =
    value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)

private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
