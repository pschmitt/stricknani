package blue.anika.wolle.data.update

import blue.anika.wolle.di.ValidationClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A newer build on GitHub Releases. [version] is display text (a version number for tagged
 * releases, a short commit for the rolling build), [downloadUrl] the matching APK - or the release
 * page when no APK fits this device's ABI.
 */
data class AppUpdate(val version: String, val downloadUrl: String)

/** Outcome of one update check; [check] collapses it to "an update or nothing". */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult

    data class Available(val update: AppUpdate) : UpdateCheckResult

    /** A local, dirty or otherwise unrecognised build: no release to compare against. */
    data object NotEligible : UpdateCheckResult

    /** Offline, rate-limited or an unreadable response. */
    data object Failed : UpdateCheckResult
}

@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/**
 * Self-update check against this repo's GitHub Releases. Which release to compare against depends
 * on what is installed: a tagged build (`GIT_REVISION` is `vX.Y.Z`) follows the newest stable
 * release, a rolling `latest` build (`GIT_REVISION` is a commit hash, what Obtainium users run)
 * follows the `latest` prerelease, whose body records the commit it was built from. Anything else
 * (local or dirty builds) is never offered an update.
 *
 * Uses the unauthenticated [ValidationClient] on purpose: the main client would rewrite the URL to
 * the Stricknani server and attach the PAT to a request meant for github.com.
 */
@Singleton
class UpdateChecker
@Inject
constructor(@ValidationClient private val client: OkHttpClient, private val json: Json) {

    internal var apiBaseUrl = "https://api.github.com/repos/pschmitt/stricknani"

    /** Returns null when up to date, when this build isn't eligible, or on any network failure. */
    suspend fun check(
        gitRevision: String,
        versionName: String,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): AppUpdate? =
        (checkDetailed(gitRevision, versionName, isDebugBuild, supportedAbis)
                as? UpdateCheckResult.Available)
            ?.update

    /** Like [check], but says why there is no update (see [UpdateCheckResult]). */
    suspend fun checkDetailed(
        gitRevision: String,
        versionName: String,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val update =
                    when {
                        SEMVER.matches(gitRevision) ->
                            checkStable(versionName, isDebugBuild, supportedAbis)
                        COMMIT_HASH.matches(gitRevision) ->
                            checkRolling(gitRevision, isDebugBuild, supportedAbis)
                        else -> return@withContext UpdateCheckResult.NotEligible
                    }
                if (update == null) UpdateCheckResult.UpToDate
                else UpdateCheckResult.Available(update)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateCheckResult.Failed
            }
        }

    private fun checkStable(
        versionName: String,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): AppUpdate? {
        val release = fetch("$apiBaseUrl/releases/latest")
        val remote = release.tagName.removePrefix("v")
        if (compareVersions(remote, versionName) <= 0) return null
        return AppUpdate(remote, downloadUrl(release, isDebugBuild, supportedAbis))
    }

    private fun checkRolling(
        gitRevision: String,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): AppUpdate? {
        val release = fetch("$apiBaseUrl/releases/tags/latest")
        val remoteCommit =
            FULL_COMMIT_HASH.find(release.body.orEmpty())?.value
                ?: error("latest release names no commit")
        if (remoteCommit.startsWith(gitRevision.lowercase())) return null
        return AppUpdate(
            remoteCommit.take(SHORT_HASH_LENGTH),
            downloadUrl(release, isDebugBuild, supportedAbis),
        )
    }

    private fun fetch(url: String): GithubRelease {
        val request =
            Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned ${response.code}" }
            return json.decodeFromString(response.body.string())
        }
    }

    private fun downloadUrl(
        release: GithubRelease,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): String {
        val variant = if (isDebugBuild) "debug" else "release"
        // Release assets are per-ABI splits named `...-app-<abi>-<variant>.apk`; the suffix match
        // keeps `x86` from matching `x86_64`. Preferred ABI first, like the platform's own order.
        val asset = supportedAbis.firstNotNullOfOrNull { abi ->
            release.assets.firstOrNull { it.name.endsWith("-app-$abi-$variant.apk") }
        }
        return asset?.downloadUrl ?: release.htmlUrl
    }

    internal companion object {
        private val SEMVER = Regex("""^v?\d+\.\d+\.\d+$""")
        private val COMMIT_HASH = Regex("""^[0-9a-fA-F]{7,40}$""")
        private val FULL_COMMIT_HASH = Regex("""\b[0-9a-f]{40}\b""")
        private const val SHORT_HASH_LENGTH = 7

        /** Numeric dotted-version comparison; a missing or non-numeric part counts as 0. */
        fun compareVersions(a: String, b: String): Int {
            val left = a.split('.').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val cmp = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}
