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
data class AppUpdate(
    val version: String,
    val downloadUrl: String,
    /** The APK for this device's ABI, or null when the release has none (then only the browser). */
    val asset: UpdateAsset? = null,
    /** The release's `SHA256SUMS` asset; an in-app install is refused without it. */
    val checksumsUrl: String? = null,
    /**
     * `X.Y.Z` of a stable release (the APK must report exactly this); null for the rolling build.
     */
    val stableVersion: String? = null,
)

/** One downloadable APK of a release. */
data class UpdateAsset(val name: String, val url: String, val sizeBytes: Long)

/** Outcome of one update check; [check] collapses it to "an update or nothing". */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult

    data class Available(val update: AppUpdate) : UpdateCheckResult

    /**
     * The installed build is newer than the newest stable release (a rolling build, or a tag newer
     * than the published one). Nothing is offered: that would be a downgrade.
     */
    data object AheadOfStable : UpdateCheckResult

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
    val size: Long = 0,
)

/**
 * Which GitHub release stream an install follows. [STABLE] (the default) is the newest `vX.Y.Z`
 * release (GitHub's "latest release", never a prerelease); [LATEST] is the rolling `latest`
 * prerelease that every push to main republishes.
 */
enum class UpdateChannel {
    STABLE,
    LATEST;

    companion object {
        fun fromName(name: String?): UpdateChannel =
            entries.firstOrNull { it.name == name } ?: STABLE
    }
}

/**
 * The channel updates really follow. Latest is a developer-mode option, so with developer mode off
 * a stored Latest is ignored and updates come from Stable. Debug builds follow the same rule: both
 * tagged releases and the rolling `latest` release carry debug APKs, and a debug install picks the
 * debug asset of whichever release its channel names.
 */
fun effectiveUpdateChannel(stored: UpdateChannel, developerMode: Boolean): UpdateChannel =
    if (developerMode) stored else UpdateChannel.STABLE

/** What `BuildConfig.GIT_REVISION` says about the running build. */
internal sealed interface InstalledBuild {
    /** Built exactly at a release tag. */
    data object Tagged : InstalledBuild

    /** Built from a main commit (a bare hash, or `git describe` output `vX.Y.Z-N-g<hash>`). */
    data class Rolling(val commit: String) : InstalledBuild

    /** Local, dirty or unrecognised: there is nothing to compare against. */
    data object Unknown : InstalledBuild

    companion object {
        private val TAG = Regex("""^v?\d+\.\d+\.\d+$""")
        private val DESCRIBE = Regex("""^v?\d+\.\d+\.\d+-\d+-g([0-9a-f]{7,40})$""")
        private val HASH = Regex("""^[0-9a-f]{7,40}$""")

        fun parse(gitRevision: String): InstalledBuild {
            val rev = gitRevision.trim().lowercase()
            return when {
                TAG.matches(rev) -> Tagged
                DESCRIBE.matches(rev) -> Rolling(DESCRIBE.matchEntire(rev)!!.groupValues[1])
                HASH.matches(rev) -> Rolling(rev)
                else -> Unknown
            }
        }
    }
}

/** GitHub's `compare/<base>...<head>` verdict: what [head] is relative to [base]. */
internal enum class CompareStatus {
    AHEAD,
    IDENTICAL,
    BEHIND,
    DIVERGED;

    companion object {
        fun parse(status: String): CompareStatus =
            entries.firstOrNull { it.name.equals(status, ignoreCase = true) } ?: DIVERGED
    }
}

@Serializable internal data class GithubCompare(val status: String)

/**
 * Self-update check against this repo's GitHub Releases, per [UpdateChannel].
 *
 * The rules, since "newer" cannot be read off the rolling release's constant tag:
 * - STABLE, installed tagged build: semver of the newest stable tag against the version name; a
 *   lower stable tag means the install is [UpdateCheckResult.AheadOfStable], never a downgrade.
 * - STABLE, installed rolling build: GitHub's compare API says whether the stable tag's commit is
 *   ahead of the installed commit. Only then is the stable release newer; otherwise the install is
 *   ahead of stable (or identical) and nothing is offered. Switching LATEST to STABLE therefore
 *   never downgrades by itself.
 * - LATEST, installed rolling build: the commit the `latest` release body names (the only reliable
 *   build identity; a release body without one fails the check) against the installed commit. The
 *   same commit is up to date and costs no API call. Otherwise the compare API decides: only a
 *   `latest` commit ahead of the install is an update; identical or behind means the install is at
 *   or past `latest`, and diverged is not eligible (nothing can be claimed).
 * - A compare answered with HTTP 404 means GitHub does not know the installed commit (a local,
 *   unpushed build): [UpdateCheckResult.NotEligible], in STABLE and LATEST alike. Other HTTP errors
 *   are [UpdateCheckResult.Failed].
 * - LATEST, installed tagged build: compare API again; the rolling build is offered when its commit
 *   is ahead of the tag. Local or dirty builds are never offered an update. The APK picked is
 *   always for this package and variant (release, or debug when running the debug build), so
 *   package ids are never crossed.
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
        channel: UpdateChannel = UpdateChannel.STABLE,
    ): AppUpdate? =
        (checkDetailed(gitRevision, versionName, isDebugBuild, supportedAbis, channel)
                as? UpdateCheckResult.Available)
            ?.update

    /** Like [check], but says why there is no update (see [UpdateCheckResult]). */
    suspend fun checkDetailed(
        gitRevision: String,
        versionName: String,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
        channel: UpdateChannel = UpdateChannel.STABLE,
    ): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            val installed = InstalledBuild.parse(gitRevision)
            if (installed == InstalledBuild.Unknown)
                return@withContext UpdateCheckResult.NotEligible
            try {
                when (channel) {
                    UpdateChannel.STABLE ->
                        checkStable(
                            installed,
                            gitRevision,
                            versionName,
                            isDebugBuild,
                            supportedAbis,
                        )
                    UpdateChannel.LATEST ->
                        checkLatest(installed, gitRevision, isDebugBuild, supportedAbis)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateCheckResult.Failed
            }
        }

    private fun checkStable(
        installed: InstalledBuild,
        gitRevision: String,
        versionName: String,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): UpdateCheckResult {
        val release = fetch("$apiBaseUrl/releases/latest")
        val remote = release.tagName.removePrefix("v")
        val available =
            UpdateCheckResult.Available(
                appUpdate(release, remote, remote, isDebugBuild, supportedAbis)
            )
        return when (installed) {
            InstalledBuild.Unknown -> UpdateCheckResult.NotEligible
            InstalledBuild.Tagged -> {
                val cmp = compareVersions(remote, versionName)
                when {
                    cmp > 0 -> available
                    cmp == 0 -> UpdateCheckResult.UpToDate
                    else -> UpdateCheckResult.AheadOfStable
                }
            }
            is InstalledBuild.Rolling ->
                when (compare(installed.commit, release.tagName)) {
                    null -> UpdateCheckResult.NotEligible
                    CompareStatus.AHEAD -> available
                    CompareStatus.IDENTICAL -> UpdateCheckResult.UpToDate
                    CompareStatus.BEHIND,
                    CompareStatus.DIVERGED -> UpdateCheckResult.AheadOfStable
                }
        }
    }

    private fun checkLatest(
        installed: InstalledBuild,
        gitRevision: String,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): UpdateCheckResult {
        val release = fetch("$apiBaseUrl/releases/tags/latest")
        val remoteCommit =
            FULL_COMMIT_HASH.find(release.body.orEmpty())?.value
                ?: error("latest release names no commit")
        val available =
            UpdateCheckResult.Available(
                appUpdate(
                    release,
                    remoteCommit.take(SHORT_HASH_LENGTH),
                    null,
                    isDebugBuild,
                    supportedAbis,
                )
            )
        return when (installed) {
            InstalledBuild.Unknown -> UpdateCheckResult.NotEligible
            is InstalledBuild.Rolling ->
                if (remoteCommit.startsWith(installed.commit)) UpdateCheckResult.UpToDate
                else
                    when (compare(installed.commit, remoteCommit)) {
                        null -> UpdateCheckResult.NotEligible
                        CompareStatus.AHEAD -> available
                        CompareStatus.IDENTICAL,
                        CompareStatus.BEHIND -> UpdateCheckResult.UpToDate
                        CompareStatus.DIVERGED -> UpdateCheckResult.NotEligible
                    }
            InstalledBuild.Tagged ->
                when (compare(gitRevision, remoteCommit)) {
                    null -> UpdateCheckResult.NotEligible
                    CompareStatus.AHEAD -> available
                    // The rolling build is the same as, or behind, the tagged one.
                    else -> UpdateCheckResult.UpToDate
                }
        }
    }

    /**
     * What [head] is relative to [base] (hashes or tag names), or null when GitHub answers 404: it
     * does not know [base], a local build that was never pushed.
     */
    private fun compare(base: String, head: String): CompareStatus? =
        fetchJsonOrNull<GithubCompare>("$apiBaseUrl/compare/$base...$head?per_page=1")?.let {
            CompareStatus.parse(it.status)
        }

    private fun fetch(url: String): GithubRelease = fetchJson(url)

    private inline fun <reified T> fetchJson(url: String): T =
        fetchJsonOrNull(url, notFoundIsNull = false)!!

    /** Like [fetchJson], but returns null for HTTP 404 when [notFoundIsNull]. */
    private inline fun <reified T> fetchJsonOrNull(
        url: String,
        notFoundIsNull: Boolean = true,
    ): T? {
        val request =
            Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { response ->
            if (notFoundIsNull && response.code == HTTP_NOT_FOUND) return null
            check(response.isSuccessful) { "GitHub returned ${response.code}" }
            return json.decodeFromString<T>(response.body.string())
        }
    }

    private fun appUpdate(
        release: GithubRelease,
        version: String,
        stableVersion: String?,
        isDebugBuild: Boolean,
        supportedAbis: List<String>,
    ): AppUpdate {
        val asset = selectApkAsset(release.assets, supportedAbis, isDebugBuild)
        return AppUpdate(
            version = version,
            downloadUrl = asset?.downloadUrl ?: release.htmlUrl,
            asset = asset?.let { UpdateAsset(it.name, it.downloadUrl, it.size) },
            checksumsUrl = release.assets.firstOrNull { it.name == CHECKSUMS_ASSET }?.downloadUrl,
            stableVersion = stableVersion,
        )
    }

    internal companion object {
        private val FULL_COMMIT_HASH = Regex("""\b[0-9a-f]{40}\b""")
        private const val SHORT_HASH_LENGTH = 7
        private const val HTTP_NOT_FOUND = 404
        const val CHECKSUMS_ASSET = "SHA256SUMS"

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

/**
 * The APK of [assets] for the first of [supportedAbis] that has one. Release assets are per-ABI
 * splits named `...-app-<abi>-<variant>.apk`; the suffix match keeps `x86` from matching `x86_64`,
 * and the preferred ABI comes first, like the platform's own order.
 */
internal fun selectApkAsset(
    assets: List<GithubAsset>,
    supportedAbis: List<String>,
    isDebugBuild: Boolean,
): GithubAsset? {
    val variant = if (isDebugBuild) "debug" else "release"
    return supportedAbis.firstNotNullOfOrNull { abi ->
        assets.firstOrNull { it.name.endsWith("-app-$abi-$variant.apk") }
    }
}
