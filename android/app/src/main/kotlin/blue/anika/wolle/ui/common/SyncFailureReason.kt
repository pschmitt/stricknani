package blue.anika.wolle.ui.common

/** A safe, user-facing category for a failed queued sync operation. */
enum class SyncFailureReason {
    AUTHENTICATION,
    SERVER,
    REJECTED,
    NETWORK,
    UNKNOWN,
}

/**
 * Classifies recognizable transport messages; arbitrary server text stays
 * [SyncFailureReason.UNKNOWN].
 */
fun syncFailureReason(message: String?): SyncFailureReason {
    if (message == null) return SyncFailureReason.UNKNOWN

    val statusCode = HTTP_STATUS_PREFIX.find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (statusCode != null) {
        return when {
            statusCode == 401 -> SyncFailureReason.AUTHENTICATION
            statusCode == 408 -> SyncFailureReason.NETWORK
            statusCode == 429 -> SyncFailureReason.SERVER
            statusCode >= 500 -> SyncFailureReason.SERVER
            statusCode in 400..499 -> SyncFailureReason.REJECTED
            else -> SyncFailureReason.UNKNOWN
        }
    }

    return if (NETWORK_FAILURE_MARKERS.any { message.contains(it, ignoreCase = true) }) {
        SyncFailureReason.NETWORK
    } else {
        SyncFailureReason.UNKNOWN
    }
}

private val HTTP_STATUS_PREFIX = Regex("^HTTP (\\d{3})\\b")
private val NETWORK_FAILURE_MARKERS =
    listOf("Unable to resolve host", "Failed to connect to", "timeout")
