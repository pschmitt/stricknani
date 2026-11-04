package blue.anika.wolle.data.update

import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The repository whose release assets the app may install. */
internal const val TRUSTED_REPO_PATH = "/pschmitt/stricknani/releases/download/"

/**
 * Only HTTPS release assets of this repository may be installed. The check is on the URL the
 * release metadata names; GitHub then redirects to its asset CDN, which [isTrustedRedirectHost]
 * covers.
 */
internal fun isTrustedAssetUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    return parsed.scheme == "https" &&
        parsed.host == "github.com" &&
        parsed.encodedPath.startsWith(TRUSTED_REPO_PATH)
}

/** Hosts GitHub redirects release downloads to; everything must stay on HTTPS. */
internal fun isTrustedRedirectHost(host: String): Boolean =
    host == "github.com" || host.endsWith(".githubusercontent.com")

/**
 * Parses `sha256sum` output (`<64 hex> <name>`, `*` marks binary mode) into name to lowercase hex.
 * Malformed lines are ignored.
 */
internal fun parseSha256Sums(text: String): Map<String, String> =
    text
        .lineSequence()
        .mapNotNull { line ->
            val match = SHA256_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            match.groupValues[2] to match.groupValues[1].lowercase()
        }
        .toMap()

private val SHA256_LINE = Regex("""([0-9a-fA-F]{64})\s+\*?(.+)""")

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private const val BUFFER_SIZE = 64 * 1024

/** What the guard needs to know about an installed or a downloaded APK. */
data class ApkFacts(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    /** SHA-256 (lowercase hex) of every current signing certificate. */
    val signerDigests: Set<String>,
)

/**
 * Whether [archive] may replace [installed]; null when it may, else the reason. Android enforces
 * the same signing key itself, but a refusal before the session is cheaper and clearer.
 */
internal fun checkInstallable(
    installed: ApkFacts,
    archive: ApkFacts,
    stableVersion: String?,
): UpdateFailure? =
    when {
        archive.packageName != installed.packageName -> UpdateFailure.WRONG_PACKAGE
        archive.signerDigests.isEmpty() ||
            installed.signerDigests.isEmpty() ||
            archive.signerDigests != installed.signerDigests -> UpdateFailure.SIGNATURE_MISMATCH
        archive.versionCode < installed.versionCode -> UpdateFailure.DOWNGRADE
        // Version names only mean something for stable releases; rolling builds are ordered by
        // commit (see UpdateChecker), and their name may lag (0.1.0 without a tag in history).
        stableVersion != null && isOlderVersionName(archive.versionName, installed.versionName) ->
            UpdateFailure.DOWNGRADE
        stableVersion != null && archive.versionName != stableVersion -> UpdateFailure.WRONG_VERSION
        else -> null
    }

private fun isOlderVersionName(candidate: String?, installed: String?): Boolean {
    if (candidate == null || installed == null) return false
    val numeric = Regex("""\d+(\.\d+)*""")
    if (!numeric.matches(candidate) || !numeric.matches(installed)) return false
    return UpdateChecker.compareVersions(candidate, installed) < 0
}

/**
 * Whether an install can run without a system confirmation: Android 12+, "install unknown apps"
 * granted, and this app is the installer of record of itself (true after its first in-app update).
 */
internal fun silentInstallPossible(
    sdkInt: Int,
    canRequestInstalls: Boolean,
    installerPackage: String?,
    ownPackage: String,
): Boolean = sdkInt >= 31 && canRequestInstalls && installerPackage == ownPackage
