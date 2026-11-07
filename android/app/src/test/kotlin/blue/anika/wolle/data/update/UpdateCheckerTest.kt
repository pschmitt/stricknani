package blue.anika.wolle.data.update

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {

    private val server = MockWebServer()
    private val remoteCommit = "a".repeat(40)

    private lateinit var checker: UpdateChecker

    @Before
    fun setUp() {
        server.start()
        checker =
            UpdateChecker(OkHttpClient(), Json { ignoreUnknownKeys = true }).apply {
                apiBaseUrl = server.url("/repos/x/y").toString().trimEnd('/')
            }
    }

    @After fun tearDown() = server.shutdown()

    private fun releaseJson(tag: String, body: String = "") =
        """
        {"tag_name":"$tag","html_url":"https://example.test/release","body":"$body","assets":[
          {"name":"stricknani-$tag-app-x86-release.apk","browser_download_url":"https://example.test/x86.apk"},
          {"name":"stricknani-$tag-app-x86_64-release.apk","browser_download_url":"https://example.test/x86_64.apk"},
          {"name":"stricknani-$tag-app-arm64-v8a-release.apk","browser_download_url":"https://example.test/arm64.apk"},
          {"name":"stricknani-$tag-app-arm64-v8a-debug.apk","browser_download_url":"https://example.test/arm64-debug.apk"}
        ]}
        """
            .trimIndent()

    private val abis = listOf("arm64-v8a", "armeabi-v7a")

    private fun check(
        revision: String,
        version: String = "0.1.0",
        debug: Boolean = false,
        channel: UpdateChannel = UpdateChannel.STABLE,
    ) = runBlocking { checker.check(revision, version, debug, abis, channel) }

    private fun detailed(
        revision: String,
        version: String = "0.1.0",
        channel: UpdateChannel = UpdateChannel.STABLE,
    ) = runBlocking { checker.checkDetailed(revision, version, false, abis, channel) }

    private fun compareJson(status: String) = """{"status":"$status","ahead_by":1}"""

    @Test
    fun `tagged build gets newer stable release with matching abi apk`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))

        val update = check(revision = "v1.1.0", version = "1.1.0")

        assertEquals("1.2.0", update?.version)
        assertEquals("https://example.test/arm64.apk", update?.downloadUrl)
        assertEquals("1.2.0", update?.stableVersion)
        assertEquals("stricknani-v1.2.0-app-arm64-v8a-release.apk", update?.asset?.name)
        assertEquals("/repos/x/y/releases/latest", server.takeRequest().path)
    }

    @Test
    fun `tagged build on newest release gets nothing`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.1.0")))

        assertNull(check(revision = "v1.1.0", version = "1.1.0"))
    }

    @Test
    fun `version comparison is numeric not lexical`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.10.0")))

        assertEquals("1.10.0", check(revision = "v1.9.0", version = "1.9.0")?.version)
    }

    @Test
    fun `rolling build behind latest prerelease gets update`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("ahead")))

        val update = check(revision = "b".repeat(12), channel = UpdateChannel.LATEST)

        assertEquals("aaaaaaa", update?.version)
        assertEquals("https://example.test/arm64.apk", update?.downloadUrl)
        assertNull(update?.stableVersion)
        assertEquals("/repos/x/y/releases/tags/latest", server.takeRequest().path)
        assertEquals(
            "/repos/x/y/compare/${"b".repeat(12)}...$remoteCommit?per_page=1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `rolling build on latest commit gets nothing and makes no compare request`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))

        assertEquals(
            UpdateCheckResult.UpToDate,
            detailed(remoteCommit.take(12), channel = UpdateChannel.LATEST),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rolling build ahead of latest is up to date, not offered the older latest`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("behind")))
        assertEquals(
            UpdateCheckResult.UpToDate,
            detailed("b".repeat(12), channel = UpdateChannel.LATEST),
        )

        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("identical")))
        assertEquals(
            UpdateCheckResult.UpToDate,
            detailed("b".repeat(12), channel = UpdateChannel.LATEST),
        )
    }

    @Test
    fun `rolling build diverged from latest is not eligible`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("diverged")))

        assertEquals(
            UpdateCheckResult.NotEligible,
            detailed("b".repeat(12), channel = UpdateChannel.LATEST),
        )
    }

    @Test
    fun `a commit unknown to github is not eligible on either channel`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(
            UpdateCheckResult.NotEligible,
            detailed("b".repeat(12), channel = UpdateChannel.LATEST),
        )

        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(UpdateCheckResult.NotEligible, detailed("b".repeat(12)))
    }

    @Test
    fun `other compare errors stay failed`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(
            UpdateCheckResult.Failed,
            detailed("b".repeat(12), channel = UpdateChannel.LATEST),
        )

        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateCheckResult.Failed, detailed("b".repeat(12)))
    }

    @Test
    fun `debug build gets the debug apk`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("ahead")))

        assertEquals(
            "https://example.test/arm64-debug.apk",
            check("b".repeat(12), debug = true, channel = UpdateChannel.LATEST)?.downloadUrl,
        )
    }

    @Test
    fun `debug build on the stable channel gets the debug apk of the newest tag`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.5.0")))

        val update = check(revision = "v1.4.0", version = "1.4.0", debug = true)

        assertEquals("1.5.0", update?.version)
        assertEquals("https://example.test/arm64-debug.apk", update?.downloadUrl)
        assertEquals("stricknani-v1.5.0-app-arm64-v8a-debug.apk", update?.asset?.name)
        assertEquals("/repos/x/y/releases/latest", server.takeRequest().path)
    }

    @Test
    fun `debug build on the latest channel asks the rolling release for its debug apk`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("ahead")))

        val update = check("b".repeat(12), debug = true, channel = UpdateChannel.LATEST)

        assertEquals("stricknani-latest-app-arm64-v8a-debug.apk", update?.asset?.name)
        assertEquals("/repos/x/y/releases/tags/latest", server.takeRequest().path)
    }

    @Test
    fun `no matching abi falls back to release page`() {
        server.enqueue(MockResponse().setBody(releaseJson("v2.0.0")))

        val update = runBlocking { checker.check("v1.0.0", "1.0.0", false, listOf("riscv64")) }

        assertEquals("https://example.test/release", update?.downloadUrl)
    }

    @Test
    fun `dirty or local builds are never checked`() {
        assertNull(check(revision = "abcdef123456-dirty"))
        assertNull(check(revision = "unknown"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `network errors are swallowed`() {
        server.enqueue(MockResponse().setResponseCode(403))

        assertNull(check(revision = "v1.0.0", version = "1.0.0"))
    }

    @Test
    fun `detailed result tells up to date, ineligible and failed apart`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.1.0")))
        val abis = listOf("arm64-v8a")
        fun detailed(rev: String, ver: String) = runBlocking {
            checker.checkDetailed(rev, ver, false, abis)
        }

        assertEquals(UpdateCheckResult.UpToDate, detailed("v1.1.0", "1.1.0"))
        assertEquals(UpdateCheckResult.NotEligible, detailed("unknown", "1.1.0"))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(UpdateCheckResult.Failed, detailed("v1.1.0", "1.1.0"))
    }

    @Test
    fun `stable channel never takes the rolling prerelease`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))

        check(revision = "v1.1.0", version = "1.1.0")

        assertEquals("/repos/x/y/releases/latest", server.takeRequest().path)
    }

    @Test
    fun `tagged build newer than the newest stable is ahead, not downgraded`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.0.0")))

        assertEquals(
            UpdateCheckResult.AheadOfStable,
            detailed(revision = "v1.1.0", version = "1.1.0"),
        )
    }

    @Test
    fun `rolling build on stable is offered the tag only when the tag is ahead of it`() {
        val rolling = "b".repeat(12)
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))
        server.enqueue(MockResponse().setBody(compareJson("ahead")))

        val result = detailed(rolling)

        assertEquals("1.2.0", (result as UpdateCheckResult.Available).update.version)
        server.takeRequest()
        assertEquals(
            "/repos/x/y/compare/$rolling...v1.2.0?per_page=1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `rolling build ahead of stable is told so instead of downgraded`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))
        server.enqueue(MockResponse().setBody(compareJson("behind")))
        assertEquals(UpdateCheckResult.AheadOfStable, detailed("b".repeat(12)))

        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))
        server.enqueue(MockResponse().setBody(compareJson("diverged")))
        assertEquals(UpdateCheckResult.AheadOfStable, detailed("b".repeat(12)))

        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))
        server.enqueue(MockResponse().setBody(compareJson("identical")))
        assertEquals(UpdateCheckResult.UpToDate, detailed("b".repeat(12)))
    }

    @Test
    fun `git describe revisions count as rolling builds`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))
        server.enqueue(MockResponse().setBody(compareJson("behind")))

        assertEquals(
            UpdateCheckResult.AheadOfStable,
            detailed("v1.1.0-5-gabcdef012345", version = "1.1.0"),
        )
        server.takeRequest()
        assertEquals(
            "/repos/x/y/compare/abcdef012345...v1.2.0?per_page=1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `tagged build on the latest channel gets the rolling build when it is ahead`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("ahead")))

        val update = check("v1.1.0", "1.1.0", channel = UpdateChannel.LATEST)

        assertEquals("aaaaaaa", update?.version)
        // The release APK of the rolling build, never the debug package.
        assertEquals("https://example.test/arm64.apk", update?.downloadUrl)
        server.takeRequest()
        assertEquals(
            "/repos/x/y/compare/v1.1.0...$remoteCommit?per_page=1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `tagged build not behind the rolling build gets nothing on the latest channel`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))
        server.enqueue(MockResponse().setBody(compareJson("behind")))

        assertEquals(
            UpdateCheckResult.UpToDate,
            detailed("v1.1.0", "1.1.0", UpdateChannel.LATEST),
        )
    }

    @Test
    fun `latest release without a commit fails the rolling check`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "no hash here")))

        assertEquals(
            UpdateCheckResult.Failed,
            detailed("b".repeat(12), channel = UpdateChannel.LATEST),
        )
    }

    @Test
    fun `installed build identity is read from the git revision`() {
        assertEquals(InstalledBuild.Tagged, InstalledBuild.parse("v1.2.3"))
        assertEquals(InstalledBuild.Rolling("abcdef0"), InstalledBuild.parse("abcdef0"))
        assertEquals(
            InstalledBuild.Rolling("9a7bfc70abcd"),
            InstalledBuild.parse("v1.0.0-12-g9a7bfc70abcd"),
        )
        assertEquals(InstalledBuild.Unknown, InstalledBuild.parse("v1.0.0-12-g9a7bfc70abcd-dirty"))
        assertEquals(InstalledBuild.Unknown, InstalledBuild.parse("unknown"))
    }

    @Test
    fun `channel names round trip and default to stable`() {
        assertEquals(UpdateChannel.LATEST, UpdateChannel.fromName("LATEST"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromName(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromName("nonsense"))
    }

    @Test
    fun `debug build honours the stored channel without developer mode`() {
        assertEquals(
            UpdateChannel.LATEST,
            effectiveUpdateChannel(
                UpdateChannel.LATEST,
                developerMode = false,
                isDebugBuild = true,
            ),
        )
        assertEquals(
            UpdateChannel.STABLE,
            effectiveUpdateChannel(
                UpdateChannel.LATEST,
                developerMode = false,
                isDebugBuild = false,
            ),
        )
    }

    @Test
    fun `channel choice is shown for debug builds, release builds need developer mode`() {
        assertEquals(
            true,
            updateChannelChoiceVisible(true, isDebugBuild = true, developerMode = false),
        )
        assertEquals(
            true,
            updateChannelChoiceVisible(true, isDebugBuild = false, developerMode = true),
        )
        assertEquals(
            false,
            updateChannelChoiceVisible(true, isDebugBuild = false, developerMode = false),
        )
        assertEquals(
            false,
            updateChannelChoiceVisible(false, isDebugBuild = true, developerMode = true),
        )
    }

    @Test
    fun `developer mode off falls back to stable whatever was stored`() {
        assertEquals(
            UpdateChannel.STABLE,
            effectiveUpdateChannel(UpdateChannel.LATEST, developerMode = false),
        )
        assertEquals(
            UpdateChannel.LATEST,
            effectiveUpdateChannel(UpdateChannel.LATEST, developerMode = true),
        )
        assertEquals(
            UpdateChannel.STABLE,
            effectiveUpdateChannel(UpdateChannel.STABLE, developerMode = true),
        )
    }
}
