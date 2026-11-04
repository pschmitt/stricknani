package blue.anika.wolle.data.errorreporting

import android.content.Context
import blue.anika.wolle.BuildConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
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
        if (running == null) return
        Sentry.close()
        running = null
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
