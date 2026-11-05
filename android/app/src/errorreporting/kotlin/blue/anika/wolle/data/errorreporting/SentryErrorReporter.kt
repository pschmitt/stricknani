package blue.anika.wolle.data.errorreporting

import android.content.Context
import android.util.Log
import blue.anika.wolle.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Sentry-backed [ErrorReporter]; compiled only into GitHub APK builds.
 *
 * It reports to the tunnel on the user's own Stricknani server (the DSN it is given points there),
 * so the Sentry host never sees the phone. Only errors are sent: no performance data, sessions,
 * screenshots, view hierarchies, breadcrumbs or personal data. Events are scrubbed in
 * [SentryEventScrubber]. The app's own on-device crash report
 * ([blue.anika.wolle.crash.CrashReportInstaller]) keeps working: Sentry's handler delegates to the
 * one installed before it.
 */
@Singleton
class SentryErrorReporter @Inject constructor(@ApplicationContext private val context: Context) :
    ErrorReporter {
    override val supported = true

    private var running: ErrorReportingConfig? = null

    @Synchronized
    override fun start(config: ErrorReportingConfig) {
        if (running == config) return
        if (running != null) Sentry.close()
        SentryAndroid.init(context) { options -> configure(options, config) }
        running = config
    }

    @Synchronized
    override fun stop() {
        if (running != null) Sentry.close()
        running = null
    }

    @Synchronized
    override fun reportSyncFailure(report: SyncFailureReport) {
        if (running == null) {
            Log.i("SyncReplay", "Sentry sync failure skipped: reporting disabled or unavailable")
            return
        }
        val safeReport = SyncFailureReportSanitizer.sanitize(report)
        val eventId = Sentry.captureEvent(syncFailureEvent(safeReport))
        Log.i("SyncReplay", "Sentry sync failure event queued: id=$eventId")
    }

    private fun configure(options: SentryAndroidOptions, config: ErrorReportingConfig) {
        options.dsn = config.dsn
        options.environment = config.environment
        options.release =
            ErrorReportRelease.name(BuildConfig.VERSION_NAME, BuildConfig.GIT_REVISION)
        options.dist = BuildConfig.GIT_REVISION
        options.setTag(ErrorReportRelease.COMPONENT_TAG, ErrorReportRelease.COMPONENT_ANDROID)
        options.isSendDefaultPii = false
        options.isAttachScreenshot = false
        options.isAttachViewHierarchy = false
        options.isEnableAutoSessionTracking = false
        options.isEnableUserInteractionBreadcrumbs = false
        options.isEnableActivityLifecycleBreadcrumbs = false
        options.isEnableAppComponentBreadcrumbs = false
        options.isEnableSystemEventBreadcrumbs = false
        options.isEnableNetworkEventBreadcrumbs = false
        options.isEnableAppLifecycleBreadcrumbs = false
        options.maxBreadcrumbs = 0
        options.tracesSampleRate = null
        options.setBeforeSend { event, _ -> SentryEventScrubber.scrub(event) }
        options.setBeforeBreadcrumb { crumb: Breadcrumb, _ -> null }
    }
}

internal fun syncFailureEvent(report: SyncFailureReport): SentryEvent {
    val safeReport = SyncFailureReportSanitizer.sanitize(report)
    val sanitizedException =
        Throwable(safeReport.kind.eventMessage).apply {
            stackTrace = safeReport.stackTrace.toTypedArray()
        }
    return SentryEvent(sanitizedException).apply {
        message = Message().apply { message = safeReport.kind.eventMessage }
        level = SentryLevel.ERROR
        setTag(ErrorReportRelease.COMPONENT_TAG, ErrorReportRelease.COMPONENT_ANDROID)
        setTag("operation", safeReport.operation)
        setTag("failure_class", safeReport.failureClass.tagValue)
        setTag("http_status", safeReport.httpStatus.toString())
        fingerprints =
            listOf(
                "android-sync-failure",
                safeReport.kind.tagValue,
                safeReport.operation,
                safeReport.failureClass.tagValue,
                safeReport.httpStatus.toString(),
            )
    }
}

/** Removes everything identifying from an event before it is sent. */
object SentryEventScrubber {
    fun scrub(event: SentryEvent): SentryEvent {
        event.user = null
        event.request = null
        event.serverName = null
        event.breadcrumbs = null
        event.setExtras(emptyMap())
        event.message?.let { message ->
            message.message = message.message?.let(ErrorReportScrubber::text)
            message.formatted = message.formatted?.let(ErrorReportScrubber::text)
            message.params = null
        }
        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let(ErrorReportScrubber::text)
        }
        event.threads?.forEach { thread ->
            thread.name = thread.name?.let(ErrorReportScrubber::text)
        }
        return event
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SentryReporterModule {
    @Binds @SentryErrorReporting abstract fun reporter(impl: SentryErrorReporter): ErrorReporter
}
