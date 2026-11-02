package blue.anika.wolle.ui.navigation

import java.net.URI

/**
 * Parses a Stricknani web URL's path into the matching in-app [Route] (SNA-17). The host isn't
 * checked against the currently-configured server - like the sibling apps (nyetbox's
 * `NetBoxUrlParser`), a Stricknani install is single-server-per-device, so any matching path is
 * assumed to belong to whichever server this device is currently connected to. This is also why the
 * manifest's intent-filters (`AndroidManifest.xml`) use `android:host="*"` rather than
 * `android:autoVerify` - see that file's comment for why a single APK can't declare one verified
 * host across arbitrary self-hosted domains.
 */
object DeepLinkParser {
    // Accept only the server's explicit legacy and SPA route prefixes. Do not accept arbitrary
    // deployment prefixes: this parser has no configured-base-path context to validate one.
    private val PROJECT_PATH = Regex("""^/(?:app/)?projects/(\d+)(?:/.*)?$""")
    private val YARN_PATH = Regex("""^/(?:yarn|app/yarns)/(\d+)(?:/.*)?$""")

    fun parse(uriString: String?): Route? {
        val path = uriString?.let { runCatching { URI(it).rawPath }.getOrNull() } ?: return null
        projectId(path)?.let {
            return Route.ProjectDetail(it)
        }
        yarnId(path)?.let {
            return Route.YarnDetail(it)
        }
        return null
    }

    private fun projectId(path: String): Int? = parsePositiveId(PROJECT_PATH, path)

    private fun yarnId(path: String): Int? = parsePositiveId(YARN_PATH, path)

    private fun parsePositiveId(pattern: Regex, path: String): Int? =
        pattern.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}
