package blue.anika.wolle.data.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerificationTest {
    private val hexA = "a".repeat(64)
    private val hexB = "B".repeat(64)

    @Test
    fun `parses sha256sum output in text and binary mode`() {
        val sums =
            parseSha256Sums(
                """
                $hexA  stricknani-v1.0.0-app-arm64-v8a-release.apk
                $hexB *stricknani-v1.0.0-app-x86-release.apk
                not a checksum line
                """
                    .trimIndent()
            )

        assertEquals(hexA, sums["stricknani-v1.0.0-app-arm64-v8a-release.apk"])
        assertEquals(hexB.lowercase(), sums["stricknani-v1.0.0-app-x86-release.apk"])
        assertEquals(2, sums.size)
    }

    @Test
    fun `sha256 of a file matches the known digest`() {
        val file = File.createTempFile("sha", ".bin").apply { writeText("abc") }
        try {
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256Hex(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `only https github release assets of this repo are trusted`() {
        val base = "https://github.com/pschmitt/stricknani/releases/download/v1.0.0/a.apk"
        assertTrue(isTrustedAssetUrl(base))
        assertFalse(isTrustedAssetUrl(base.replace("https", "http")))
        assertFalse(isTrustedAssetUrl(base.replace("pschmitt/stricknani", "evil/stricknani")))
        assertFalse(isTrustedAssetUrl(base.replace("github.com", "github.com.evil.test")))
        assertFalse(isTrustedAssetUrl("https://github.com/pschmitt/stricknani/archive/main.zip"))
        assertFalse(isTrustedAssetUrl("not a url"))
    }

    @Test
    fun `redirects may only go to github hosts`() {
        assertTrue(isTrustedRedirectHost("github.com"))
        assertTrue(isTrustedRedirectHost("release-assets.githubusercontent.com"))
        assertFalse(isTrustedRedirectHost("evilgithubusercontent.com"))
        assertFalse(isTrustedRedirectHost("example.test"))
    }

    @Test
    fun `asset selection prefers the first abi and keeps x86 apart from x86_64`() {
        val assets =
            listOf(
                    "s-v1-app-x86-release.apk",
                    "s-v1-app-x86_64-release.apk",
                    "s-v1-app-arm64-v8a-release.apk",
                    "s-v1-app-arm64-v8a-debug.apk",
                    "SHA256SUMS",
                )
                .map { GithubAsset(it, "https://x/$it") }

        assertEquals(
            "s-v1-app-x86_64-release.apk",
            selectApkAsset(assets, listOf("x86_64", "x86"), false)?.name,
        )
        assertEquals(
            "s-v1-app-x86-release.apk",
            selectApkAsset(assets, listOf("x86"), false)?.name,
        )
        assertEquals(
            "s-v1-app-arm64-v8a-debug.apk",
            selectApkAsset(assets, listOf("arm64-v8a"), true)?.name,
        )
        assertNull(selectApkAsset(assets, listOf("riscv64"), false))
    }

    private val installed = facts(versionName = "1.0.0", versionCode = 1)

    private fun facts(
        pkg: String = "blue.anika.wolle",
        versionName: String? = "1.1.0",
        versionCode: Long = 1,
        signers: Set<String> = setOf("cert1"),
    ) = ApkFacts(pkg, versionName, versionCode, signers)

    @Test
    fun `a newer build of the same app and key is installable`() {
        assertNull(checkInstallable(installed, facts(), stableVersion = "1.1.0"))
    }

    @Test
    fun `the same version is allowed for rolling builds`() {
        assertNull(checkInstallable(installed, facts(versionName = "1.0.0"), null))
    }

    @Test
    fun `another package is refused`() {
        assertEquals(
            UpdateFailure.WRONG_PACKAGE,
            checkInstallable(installed, facts(pkg = "blue.anika.wolle.debug"), null),
        )
    }

    @Test
    fun `another signing certificate is refused`() {
        assertEquals(
            UpdateFailure.SIGNATURE_MISMATCH,
            checkInstallable(installed, facts(signers = setOf("other")), null),
        )
        assertEquals(
            UpdateFailure.SIGNATURE_MISMATCH,
            checkInstallable(installed, facts(signers = setOf("cert1", "cert2")), null),
        )
        assertEquals(
            UpdateFailure.SIGNATURE_MISMATCH,
            checkInstallable(installed, facts(signers = emptySet()), null),
        )
    }

    @Test
    fun `downgrades are refused by version code and by version name`() {
        val newer = facts(versionName = "2.0.0", versionCode = 5)
        assertEquals(
            UpdateFailure.DOWNGRADE,
            checkInstallable(newer, facts(versionName = "2.1.0", versionCode = 4), null),
        )
        assertEquals(
            UpdateFailure.DOWNGRADE,
            checkInstallable(newer, facts(versionName = "1.9.9", versionCode = 5), "1.9.9"),
        )
        assertEquals(
            UpdateFailure.DOWNGRADE,
            checkInstallable(facts(versionName = "1.10.0"), facts(versionName = "1.9.0"), "1.9.0"),
        )
    }

    @Test
    fun `rolling builds are not judged by version name`() {
        // A rolling build made without a tag in its history is named 0.1.0.
        assertNull(
            checkInstallable(facts(versionName = "1.0.0"), facts(versionName = "0.1.0"), null)
        )
    }

    @Test
    fun `a stable release must report the announced version`() {
        assertEquals(
            UpdateFailure.WRONG_VERSION,
            checkInstallable(installed, facts(versionName = "1.2.0"), stableVersion = "1.1.0"),
        )
    }

    @Test
    fun `silent install needs android 12, the permission and being the installer`() {
        val own = "blue.anika.wolle"
        assertTrue(silentInstallPossible(34, true, own, own))
        assertFalse(silentInstallPossible(30, true, own, own))
        assertFalse(silentInstallPossible(34, false, own, own))
        assertFalse(silentInstallPossible(34, true, "com.android.chrome", own))
        assertFalse(silentInstallPossible(34, true, null, own))
    }
}
