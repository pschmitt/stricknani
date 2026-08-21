package blue.anika.wolle.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    @Test
    fun `format includes diagnostics and redacts API tokens`() {
        val report =
            CrashReportFormatter.format(
                threadName = "main",
                throwable =
                    IllegalStateException(
                        "request failed with sna_privateSecretValue and Authorization: Bearer sna_anotherSecret"
                    ),
                versionName = "1.2.3",
                revision = "abc1234",
                buildDate = "2026-08-02",
                timestamp = "2026-08-02T12:00:00Z",
                device = "test device",
            )

        assertTrue(report.contains("Stricknani crash report"))
        assertTrue(report.contains("Build: 1.2.3 (abc1234, 2026-08-02)"))
        assertTrue(report.contains("Device: test device"))
        assertTrue(report.contains("Thread: main"))
        assertFalse(report.contains("privateSecretValue"))
        assertFalse(report.contains("anotherSecret"))
        assertTrue(report.contains("[REDACTED_API_TOKEN]"))
    }

    @Test
    fun `handler persists report and delegates original exception`() {
        var saved: String? = null
        var delegated: Throwable? = null
        val throwable = IllegalArgumentException("boom")
        val handler =
            CrashReportHandler(
                save = { saved = it },
                delegate = Thread.UncaughtExceptionHandler { _, error -> delegated = error },
                formatter = { _, error -> "formatted: ${error.message}" },
            )

        handler.uncaughtException(Thread.currentThread(), throwable)

        assertEquals("formatted: boom", saved)
        assertEquals(throwable, delegated)
    }
}
