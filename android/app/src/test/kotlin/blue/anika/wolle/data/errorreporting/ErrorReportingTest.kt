package blue.anika.wolle.data.errorreporting

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorReportingTest {
    private val meta =
        ErrorReportingDto(
            available = true,
            dsn = "https://pub@sentry.example.test/7",
            tunnelPath = "/api/v2/telemetry/sentry",
            environment = "production",
            enabled = true,
        )

    private fun config(
        compiledIn: Boolean = true,
        meta: ErrorReportingDto? = this.meta,
        serverUrl: String = "https://knit.example.test/",
        local: Boolean = true,
    ) = ErrorReportingGate.config(compiledIn, meta, serverUrl, local)

    @Test
    fun reportsOnlyThroughTheServersTunnelWhenEverythingAllowsIt() {
        assertEquals(
            ErrorReportingConfig(
                "https://pub@knit.example.test/api/v2/telemetry/sentry/7",
                "production",
            ),
            config(),
        )
    }

    @Test
    fun staysOffWithoutTheSdkTheServerOrTheUsersConsent() {
        // The Play build has no SDK.
        assertNull(config(compiledIn = false))
        // Server without error reporting, or an older server that says nothing.
        assertNull(config(meta = null))
        assertNull(config(meta = meta.copy(available = false)))
        // Opted out on the account (for example in the web app) or on this device.
        assertNull(config(meta = meta.copy(enabled = false)))
        assertNull(config(local = false))
        // Unusable details.
        assertNull(config(meta = meta.copy(dsn = null)))
        assertNull(config(meta = meta.copy(tunnelPath = "https://evil.test/x")))
        assertNull(config(meta = meta.copy(tunnelPath = "//evil.test/x")))
        assertNull(config(serverUrl = ""))
    }

    @Test
    fun neverReportsOverPlainHttp() {
        // A LAN or development server: silently no reports, whatever else allows them.
        assertNull(config(serverUrl = "http://knit.example.test/"))
        assertNull(config(serverUrl = "http://192.168.1.20:7674"))
        assertNull(config(serverUrl = "http://localhost:7674"))
        assertNull(config(serverUrl = "ftp://knit.example.test"))
        assertNull(config(serverUrl = "knit.example.test"))
        // The scheme is case-insensitive and surrounding space is ignored.
        assertEquals(
            "https://pub@knit.example.test/api/v2/telemetry/sentry/7",
            config(serverUrl = " HTTPS://knit.example.test/ ")?.dsn,
        )
    }

    @Test
    fun theSwitchShowsOnOnlyWhenBothChoicesAreOn() {
        assertTrue(ErrorReportingGate.switchOn(meta, localEnabled = true))
        assertFalse(ErrorReportingGate.switchOn(meta, localEnabled = false))
        assertFalse(ErrorReportingGate.switchOn(meta.copy(enabled = false), localEnabled = true))
        assertFalse(ErrorReportingGate.switchOn(null, localEnabled = true))
    }

    @Test
    fun theDefaultIsOnAndOlderServersParseAsUnavailable() {
        assertTrue(ERROR_REPORTING_DEFAULT_ENABLED)
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString<ErrorReportingDto>("""{"available":false}""")
        assertFalse(parsed.available)
        assertTrue(parsed.enabled)
        val full =
            json.decodeFromString<ErrorReportingDto>(
                """{"available":true,"dsn":"https://k@h.test/1","tunnel_path":"/t","environment":"e","enabled":false}"""
            )
        assertEquals("/t", full.tunnelPath)
        assertFalse(full.enabled)
    }

    @Test
    fun scrubsTokensEmailsAndQueries() {
        assertEquals(
            "[REDACTED_API_TOKEN] and [email]",
            ErrorReportScrubber.text("sna_abc123DEF456 and bob@example.test"),
        )
        assertEquals(
            "Authorization: Bearer [REDACTED]",
            ErrorReportScrubber.text("Authorization: Bearer abc.def.ghi"),
        )
        assertEquals(
            "GET /x?[redacted] failed",
            ErrorReportScrubber.text("GET /x?token=abc failed"),
        )
        assertEquals("password=[REDACTED]", ErrorReportScrubber.text("password=hunter2"))
        assertEquals("[redacted]", ErrorReportScrubber.text("0123456789abcdef0123456789abcdef0123"))
        assertEquals(
            "java.lang.IllegalStateException: boom",
            ErrorReportScrubber.text("java.lang.IllegalStateException: boom"),
        )
    }

    @Test
    fun releaseNamesTheAppAndCommit() {
        assertEquals(
            "stricknani-android@1.2.3+abc1234",
            ErrorReportRelease.name("1.2.3", "abc1234"),
        )
        assertEquals("stricknani-android@1.2.3", ErrorReportRelease.name("1.2.3", "unknown"))
        assertEquals("android", ErrorReportRelease.COMPONENT_ANDROID)
    }

    @Test
    fun theSdkIsGivenADsnThatPointsAtTheServersTunnel() {
        assertEquals(
            "https://k@knit.example.test:7674/api/v2/telemetry/sentry/12",
            ErrorReportingGate.tunnelDsn(
                "https://k:secret@o1.ingest.sentry.test/12",
                "https://knit.example.test:7674/api/v2/telemetry/sentry",
            ),
        )
        // No DSN is ever built for a plain HTTP tunnel.
        assertNull(
            ErrorReportingGate.tunnelDsn(
                "https://k:secret@o1.ingest.sentry.test/12",
                "http://10.0.0.2:7674/api/v2/telemetry/sentry",
            )
        )
        assertNull(ErrorReportingGate.tunnelDsn("not a dsn", "https://x.test/t"))
        assertNull(config(meta = meta.copy(dsn = "https://sentry.example.test/7")))
    }
}
