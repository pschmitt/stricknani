package blue.anika.wolle.data.errorreporting

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.sentry.Sentry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real SDK transport check. Pass `sentryVerificationServer` to enable it. */
@RunWith(AndroidJUnit4::class)
class SentryReleaseTest {
    @Test
    fun sendsSanitizedSyncFailureThroughTheConfiguredServerTunnel() {
        val arguments = InstrumentationRegistry.getArguments()
        val suppliedServerUrl =
            arguments
                .getString("sentryVerificationServer")
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf(String::isNotEmpty)
        assumeTrue("Sentry verification server was not supplied", suppliedServerUrl != null)
        val serverUrl = requireNotNull(suppliedServerUrl)

        val meta = readErrorReportingMetadata(serverUrl)
        assumeTrue("Server Sentry reporting is not enabled", meta.available && meta.enabled)
        val config =
            ErrorReportingGate.config(
                    compiledIn = true,
                    meta = meta,
                    serverUrl = serverUrl,
                    localEnabled = true,
                )
                ?.copy(environment = "release-verification")
        assumeTrue("Server did not provide a usable HTTPS Sentry tunnel", config != null)

        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val reporter = SentryErrorReporter(context)
        try {
            reporter.start(config!!)
            assertTrue("Sentry SDK did not start", Sentry.isEnabled())
            Sentry.withScope { scope ->
                scope.setTag("verification", "android-release")
                reporter.reportSyncFailure(
                    SyncFailureReport(
                        kind = SyncFailureKind.SYNC,
                        operation = "projects",
                        failureClass = SyncFailureClass.OTHER,
                        httpStatus = 503,
                        stackTrace = Throwable().stackTrace.toList(),
                    )
                )
            }
            Sentry.flush(15_000L)
        } finally {
            reporter.stop()
        }
        assertFalse("Sentry SDK remained enabled after stop", Sentry.isEnabled())
    }

    private fun readErrorReportingMetadata(serverUrl: String): ErrorReportingDto {
        val request = Request.Builder().url("$serverUrl/api/v2/meta").get().build()
        OkHttpClient().newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Metadata request failed with HTTP ${response.code}" }
            val data = JSONObject(response.body?.string().orEmpty()).getJSONObject("data")
            val metadata = data.optJSONObject("error_reporting") ?: JSONObject()
            return ErrorReportingDto(
                available = metadata.optBoolean("available"),
                dsn = metadata.optString("dsn").takeIf(String::isNotEmpty),
                tunnelPath = metadata.optString("tunnel_path").takeIf(String::isNotEmpty),
                environment = metadata.optString("environment").takeIf(String::isNotEmpty),
                enabled = metadata.optBoolean("enabled"),
            )
        }
    }
}
