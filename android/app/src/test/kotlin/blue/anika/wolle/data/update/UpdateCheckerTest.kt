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

    @After
    fun tearDown() = server.shutdown()

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

    private fun check(revision: String, version: String = "0.1.0", debug: Boolean = false) =
        runBlocking {
            checker.check(revision, version, debug, listOf("arm64-v8a", "armeabi-v7a"))
        }

    @Test
    fun `tagged build gets newer stable release with matching abi apk`() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.0")))

        val update = check(revision = "v1.1.0", version = "1.1.0")

        assertEquals(AppUpdate("1.2.0", "https://example.test/arm64.apk"), update)
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

        val update = check(revision = "b".repeat(12))

        assertEquals(AppUpdate("aaaaaaa", "https://example.test/arm64.apk"), update)
        assertEquals("/repos/x/y/releases/tags/latest", server.takeRequest().path)
    }

    @Test
    fun `rolling build on latest commit gets nothing`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))

        assertNull(check(revision = remoteCommit.take(12)))
    }

    @Test
    fun `debug build gets the debug apk`() {
        server.enqueue(MockResponse().setBody(releaseJson("latest", "built from ($remoteCommit)")))

        assertEquals(
            "https://example.test/arm64-debug.apk",
            check("b".repeat(12), debug = true)?.downloadUrl,
        )
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
}
