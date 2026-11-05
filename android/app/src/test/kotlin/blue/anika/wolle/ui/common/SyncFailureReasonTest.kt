package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureReasonTest {
    @Test
    fun `classifies anchored Retrofit status messages`() {
        assertEquals(
            SyncFailureReason.AUTHENTICATION,
            syncFailureReason("HTTP 401 Unauthorized"),
        )
        assertEquals(SyncFailureReason.SERVER, syncFailureReason("HTTP 503 Server Error"))
        assertEquals(SyncFailureReason.NETWORK, syncFailureReason("HTTP 408 Request Timeout"))
        assertEquals(SyncFailureReason.SERVER, syncFailureReason("HTTP 429 Too Many Requests"))
        assertEquals(
            SyncFailureReason.REJECTED,
            syncFailureReason("HTTP 422 Unprocessable Entity"),
        )
        assertEquals(SyncFailureReason.UNKNOWN, syncFailureReason("prefix HTTP 401 Unauthorized"))
    }

    @Test
    fun `classifies recognizable network failure messages`() {
        assertEquals(
            SyncFailureReason.NETWORK,
            syncFailureReason("Unable to resolve host example.test"),
        )
        assertEquals(
            SyncFailureReason.NETWORK,
            syncFailureReason("Failed to connect to example.test"),
        )
        assertEquals(SyncFailureReason.NETWORK, syncFailureReason("Socket timeout"))
    }

    @Test
    fun `keeps arbitrary messages and null unknown`() {
        assertEquals(
            SyncFailureReason.UNKNOWN,
            syncFailureReason("The server rejected this project"),
        )
        assertEquals(SyncFailureReason.UNKNOWN, syncFailureReason(null))
    }
}
