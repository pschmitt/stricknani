package blue.anika.wolle.data.repository

/**
 * Names the origin of a queued project or yarn delete, so a delete nobody remembers confirming can
 * be traced afterwards (`adb logcat -s DeleteAudit`). The queue is only ever fed by a confirmed
 * delete dialog; if a delete shows up anyway, the line written when it was queued names the code
 * that queued it, and the line written when it was sent says how long it waited.
 */
object DeleteAudit {
    const val TAG = "DeleteAudit"

    private const val APP_PACKAGE = "blue.anika.wolle."
    private const val MAX_FRAMES = 10

    /**
     * The app's own frames of [stack] (framework and coroutine plumbing left out), innermost first.
     */
    fun callers(stack: Array<StackTraceElement>): String =
        stack
            .filter {
                it.className.startsWith(APP_PACKAGE) && !it.className.contains(".DeleteAudit")
            }
            .take(MAX_FRAMES)
            .joinToString(" < ") { frame ->
                "${frame.className.removePrefix(APP_PACKAGE)}.${frame.methodName}:${frame.lineNumber}"
            }
            .ifEmpty { "no app frame (coroutine resumed)" }

    /** Written when a delete enters the write queue. */
    fun queued(entityType: String, id: Int, stack: Array<StackTraceElement>): String =
        "queued delete of $entityType $id by ${callers(stack)}"

    /** Written when the queue sends a delete to the server. */
    fun sending(entityType: String, id: Int, createdAtMillis: Long, nowMillis: Long): String =
        "sending delete of $entityType $id queued ${(nowMillis - createdAtMillis) / 1000}s ago"
}
