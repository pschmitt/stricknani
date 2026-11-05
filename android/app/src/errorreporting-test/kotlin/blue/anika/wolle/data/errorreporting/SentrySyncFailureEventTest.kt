package blue.anika.wolle.data.errorreporting

import io.sentry.SentryLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SentrySyncFailureEventTest {
    @Test
    fun buildsAnEventFromOnlyStaticCopyAndSanitizedTags() {
        val event =
            syncFailureEvent(
                SyncFailureReport(
                    kind = SyncFailureKind.QUEUED_WRITE,
                    operation = "project_update?token=private",
                    failureClass = SyncFailureClass.OTHER,
                    httpStatus = 401,
                    stackTrace =
                        listOf(
                            StackTraceElement(
                                "blue.anika.wolle.sync.WriteReplayWorker",
                                "handleReplayFailure",
                                "/tmp/build/WriteReplayWorker.kt",
                                140,
                            ),
                            StackTraceElement("okhttp3.RealCall", "execute", "RealCall.kt", 123),
                        ),
                )
            )

        assertEquals("Queued write failed", event.message?.message)
        assertEquals(SentryLevel.ERROR, event.level)
        assertEquals("android", event.tags?.get(ErrorReportRelease.COMPONENT_TAG))
        assertEquals("other", event.tags?.get("operation"))
        assertEquals("other", event.tags?.get("failure_class"))
        assertEquals("401", event.tags?.get("http_status"))
        assertEquals("Queued write failed", event.throwable?.message)
        assertEquals(
            "blue.anika.wolle.sync.WriteReplayWorker",
            event.throwable?.stackTrace?.first()?.className,
        )
        assertEquals(
            "handleReplayFailure",
            event.throwable?.stackTrace?.first()?.methodName,
        )
        assertEquals("WriteReplayWorker.kt", event.throwable?.stackTrace?.first()?.fileName)
        assertEquals("okhttp3.RealCall", event.throwable?.stackTrace?.last()?.className)
        assertEquals(
            listOf("android-sync-failure", "queued_write", "other", "other", "401"),
            event.fingerprints,
        )
        assertFalse(event.message?.message.orEmpty().contains("private"))
        assertFalse(event.throwable?.message.orEmpty().contains("private"))
        assertNull(event.throwable?.cause)
    }

    @Test
    fun includesSanitizedHandledConflictStatus() {
        val event =
            syncFailureEvent(
                SyncFailureReport(
                    kind = SyncFailureKind.QUEUED_WRITE,
                    operation = "project_update",
                    failureClass = SyncFailureClass.CONFLICT,
                    httpStatus = 409,
                )
            )

        assertEquals("conflict", event.tags?.get("failure_class"))
        assertEquals("409", event.tags?.get("http_status"))
    }
}
