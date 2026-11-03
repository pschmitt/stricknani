package blue.anika.wolle.ui.settings

import java.net.URLEncoder

/**
 * Builds [Obtainium](https://github.com/ImranR98/Obtainium) deep links that add Stricknani with its
 * settings pre-filled. Format: `obtainium://app/<url-encoded JSON>`, see
 * https://wiki.obtainium.imranr.dev/deep_links/. The setting keys (`apkFilterRegEx`,
 * `includePrereleases`, `releaseDateAsVersion`) are the GitHub source's additionalSettings keys.
 */
internal object ObtainiumLink {
    const val REPO_URL = "https://github.com/pschmitt/stricknani"
    const val OBTAINIUM_RELEASES_URL = "https://github.com/ImranR98/Obtainium/releases/latest"
    const val APP_ID = "blue.anika.wolle"
    const val APP_NAME = "Stricknani"
    const val AUTHOR = "pschmitt"

    /** Matches only the release APKs: the rolling prerelease also carries `*-debug.apk` files. */
    const val RELEASE_APK_FILTER = """-release\.apk${'$'}"""

    /** Obtainium package ids: upstream build and the F-Droid flavour. */
    val PACKAGE_IDS = listOf("dev.imranr.obtainium", "dev.imranr.obtainium.fdroid")

    /**
     * [rolling] false: stable tagged releases only. True: also the rolling `latest` prerelease; its
     * tag never changes, so the release date is used as the version to detect new builds.
     */
    fun build(rolling: Boolean): String {
        val settings = buildString {
            append("{")
            append("\"apkFilterRegEx\":").append(jsonString(RELEASE_APK_FILTER))
            if (rolling) {
                append(",\"includePrereleases\":true,\"releaseDateAsVersion\":true")
            }
            append("}")
        }
        val json =
            "{\"id\":${jsonString(APP_ID)},\"url\":${jsonString(REPO_URL)}," +
                "\"author\":${jsonString(AUTHOR)},\"name\":${jsonString(APP_NAME)}," +
                "\"additionalSettings\":${jsonString(settings)}}"
        return "obtainium://app/" + urlEncode(json)
    }

    // Obtainium stores additionalSettings as a JSON string inside the app JSON.
    internal fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    internal fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
