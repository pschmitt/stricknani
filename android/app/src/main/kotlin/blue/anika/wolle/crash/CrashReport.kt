package blue.anika.wolle.crash

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import blue.anika.wolle.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

private const val CRASH_PREFS = "crash-report"
private const val PENDING_REPORT = "pending-report"

/**
 * Stores one crash report synchronously (SNA-35) so it survives the process termination that
 * follows an uncaught exception - matches nyetbox's `CrashReportStore` exactly.
 */
class CrashReportStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)

    fun save(report: String) {
        // The process may be killed immediately after an uncaught exception; apply() is not
        // sufficient here because its asynchronous write could be lost.
        preferences.edit(commit = true) { putString(PENDING_REPORT, report) }
    }

    fun takePending(): String? {
        val report = preferences.getString(PENDING_REPORT, null)
        if (report != null) {
            preferences.edit(commit = true) { remove(PENDING_REPORT) }
        }
        return report
    }
}

/** Formats only diagnostic data and redacts Stricknani's own PAT/Bearer token forms. */
object CrashReportFormatter {
    private val apiToken = Regex("(?i)\\bsna_[A-Za-z0-9_-]+\\b")
    private val authorizationHeader =
        Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer|token)\\s+)[^\\s]+")
    private val tokenParameter = Regex("(?i)((?:api[-_ ]?token|token)\\s*[:=]\\s*)[^\\s,;}\\]]+")

    fun format(
        threadName: String,
        throwable: Throwable,
        versionName: String,
        revision: String,
        buildDate: String,
        timestamp: String = Instant.now().toString(),
        device: String = "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})",
    ): String {
        val stackTrace =
            StringWriter().also { writer -> throwable.printStackTrace(PrintWriter(writer)) }
        return buildString {
            appendLine("Stricknani crash report")
            appendLine("Time: $timestamp")
            appendLine("Build: $versionName ($revision, $buildDate)")
            appendLine("Device: $device")
            appendLine("Thread: $threadName")
            appendLine()
            appendLine(sanitize(stackTrace.toString()).trimEnd())
        }
    }

    internal fun sanitize(value: String): String =
        value
            .replace(apiToken, "[REDACTED_API_TOKEN]")
            .replace(authorizationHeader, "$1[REDACTED]")
            .replace(tokenParameter, "$1[REDACTED]")
}

/** Installs a delegating handler, preserving Android's normal fatal-exception behavior. */
object CrashReportInstaller {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashReportHandler) return
        val store = CrashReportStore(context)
        Thread.setDefaultUncaughtExceptionHandler(
            CrashReportHandler(
                save = store::save,
                delegate = previous,
                formatter = { thread, throwable ->
                    CrashReportFormatter.format(
                        threadName = thread.name ?: "unknown",
                        throwable = throwable,
                        versionName = BuildConfig.VERSION_NAME,
                        revision = BuildConfig.GIT_REVISION,
                        buildDate = BuildConfig.BUILD_DATE,
                    )
                },
            )
        )
    }
}

internal class CrashReportHandler(
    private val save: (String) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val formatter: (Thread, Throwable) -> String,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { save(formatter(thread, throwable)) }
        delegate?.uncaughtException(thread, throwable)
    }
}
