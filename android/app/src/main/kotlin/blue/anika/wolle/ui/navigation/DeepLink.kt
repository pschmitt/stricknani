package blue.anika.wolle.ui.navigation

import java.net.URI

/**
 * Parses a Stricknani web URL's path into the matching in-app [Route] (SNA-17). The host isn't
 * checked against the currently-configured server - like the sibling apps (nyetbox's
 * `NetBoxUrlParser`), a Stricknani install is single-server-per-device, so any matching path is
 * assumed to belong to whichever server this device is currently connected to. This is also why
 * the manifest's intent-filters (`AndroidManifest.xml`) use `android:host="*"` rather than
 * `android:autoVerify` - see that file's comment for why a single APK can't declare one verified
 * host across arbitrary self-hosted domains.
 */
object DeepLinkParser {
    private val PROJECT_PATH = Regex("""^/projects/(\d+)(?:/.*)?$""")
    private val YARN_PATH = Regex("""^/yarn/(\d+)(?:/.*)?$""")

    fun parse(uriString: String?): Route? {
        val path = uriString?.let { runCatching { URI(it).rawPath }.getOrNull() } ?: return null
        PROJECT_PATH.matchEntire(path)?.let {
            return Route.ProjectDetail(it.groupValues[1].toInt())
        }
        YARN_PATH.matchEntire(path)?.let { return Route.YarnDetail(it.groupValues[1].toInt()) }
        return null
    }
}
