package blue.anika.wolle.data.errorreporting

import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.PATCH

/**
 * Optional, operator-configured error reporting (Sentry).
 *
 * The app has no DSN of its own. The server tells it (`error_reporting` in `GET /api/v2/meta`)
 * whether its operator set up Sentry and, if so, the public DSN and the server's own tunnel path.
 * Reports go to that tunnel on the user's Stricknani server, never straight to a Sentry host. The
 * Sentry SDK is compiled into every variant, Google Play included, unless the build opts out with
 * `-PerrorReporting=false` ([BuildConfig.ERROR_REPORTING_COMPILED]). Nothing is sent unless the
 * server offers reporting and the user has not turned it off.
 */
const val ERROR_REPORTING_DEFAULT_ENABLED = true

/**
 * `error_reporting` of `GET /api/v2/meta` (also `/session`). All fields are optional on older
 * servers.
 */
@Serializable
data class ErrorReportingDto(
    val available: Boolean = false,
    /** Public DSN, never a secret key. */
    val dsn: String? = null,
    @SerialName("tunnel_path") val tunnelPath: String? = null,
    val environment: String? = null,
    /** The signed-in user's choice on the server. */
    val enabled: Boolean = ERROR_REPORTING_DEFAULT_ENABLED,
)

/** What the SDK needs to start. */
data class ErrorReportingConfig(val dsn: String, val environment: String)

/** Decides whether reporting may run. Pure, so the rules are unit tested. */
object ErrorReportingGate {
    /**
     * A config only when the build includes the SDK, the server says error reporting is available,
     * both the user's server-side choice and this device's choice are on, and the DSN and tunnel
     * path are usable. Anything else means "do not report".
     *
     * Reports only ever travel over HTTPS: when the configured server URL is plain HTTP (a LAN or
     * development server) the app silently does not report, so "encrypted in transit" is always
     * true of what leaves the device.
     */
    fun config(
        compiledIn: Boolean,
        meta: ErrorReportingDto?,
        serverUrl: String,
        localEnabled: Boolean,
    ): ErrorReportingConfig? {
        if (!compiledIn || meta == null || !meta.available) return null
        if (!meta.enabled || !localEnabled) return null
        val dsn = meta.dsn?.trim().orEmpty()
        val path = meta.tunnelPath?.trim().orEmpty()
        val base = serverUrl.trim().trimEnd('/')
        if (dsn.isEmpty() || base.isEmpty()) return null
        if (!path.startsWith("/") || path.startsWith("//")) return null
        if (!isHttps(base)) return null
        val tunnelDsn = tunnelDsn(dsn, base + path) ?: return null
        return ErrorReportingConfig(tunnelDsn, meta.environment ?: "production")
    }

    /** Whether [url] is an `https://` URL (the scheme is case-insensitive). */
    internal fun isHttps(url: String): Boolean =
        url.trim().startsWith("https://", ignoreCase = true)

    /**
     * The Sentry Android SDK has no tunnel option, so it gets a DSN with the real key and project
     * but this server's tunnel as host and path: `https://KEY@server/api/v2/telemetry/sentry/ID`.
     * The SDK then posts to `<that path>/api/ID/envelope/`, which the server forwards to the real
     * Sentry host. Null when [dsn] is not `scheme://key@host/.../id`.
     */
    internal fun tunnelDsn(dsn: String, tunnelUrl: String): String? {
        val match = Regex("^https?://([^@/:]+)(?::[^@/]*)?@[^/]+/(?:.*/)?([0-9]+)/?$").find(dsn)
        val (key, project) = match?.destructured ?: return null
        if (!isHttps(tunnelUrl)) return null
        val host = tunnelUrl.trim().substring("https://".length).trimEnd('/')
        return "https://$key@$host/$project"
    }

    /** What the Settings switch shows: reporting is on only when both choices are on. */
    fun switchOn(meta: ErrorReportingDto?, localEnabled: Boolean): Boolean =
        meta?.enabled == true && localEnabled
}

/** Tags and release naming shared by every event, so one Sentry project can be filtered. */
object ErrorReportRelease {
    const val COMPONENT_TAG = "component"
    const val COMPONENT_ANDROID = "android"

    /** `stricknani-android@<version>+<commit>`; the commit is left out when unknown. */
    fun name(versionName: String, revision: String): String =
        "stricknani-android@$versionName" +
            if (revision.isBlank() || revision == "unknown") "" else "+$revision"
}

/** Static message families for sanitized sync-failure events. */
enum class SyncFailureKind(val eventMessage: String, val tagValue: String) {
    QUEUED_WRITE("Queued write failed", "queued_write"),
    SYNC("Sync failed", "sync"),
}

/** Bounded, non-sensitive dimensions for one handled sync failure. */
data class SyncFailureReport(
    val kind: SyncFailureKind,
    val operation: String,
    val failureClass: SyncFailureClass,
    val httpStatus: Int,
    val stackTrace: List<StackTraceElement> = emptyList(),
)

/**
 * Stable categories that remain useful when R8 obfuscates JVM class names. [reportable] is false
 * for "the server cannot be reached right now" (no network, DNS, timeouts, refused connections):
 * the app works offline and retries later, so these are not errors worth an event. Rejected
 * credentials are handled by session expiry and reauthentication, not error telemetry.
 */
enum class SyncFailureClass(val tagValue: String, val reportable: Boolean = true) {
    HTTP("http"),
    AUTHENTICATION("authentication", reportable = false),
    UNKNOWN_HOST("unknown_host", reportable = false),
    TIMEOUT("timeout", reportable = false),
    CONNECTION("connection", reportable = false),
    IO("io"),
    SERIALIZATION("serialization"),
    STATE("state"),
    ARGUMENT("argument"),
    SECURITY("security"),
    FORBIDDEN("forbidden"),
    CONFLICT("conflict"),
    OTHER("other"),
}

object SyncFailureClassifier {
    fun classify(failure: Throwable): SyncFailureClass =
        when (failure) {
            is HttpException ->
                if (failure.code() == 401) SyncFailureClass.AUTHENTICATION
                else SyncFailureClass.HTTP
            is UnknownHostException -> SyncFailureClass.UNKNOWN_HOST
            is SocketTimeoutException,
            is java.util.concurrent.TimeoutException -> SyncFailureClass.TIMEOUT
            is ConnectException -> SyncFailureClass.CONNECTION
            is SerializationException -> SyncFailureClass.SERIALIZATION
            is IOException -> SyncFailureClass.IO
            is IllegalStateException -> SyncFailureClass.STATE
            is IllegalArgumentException -> SyncFailureClass.ARGUMENT
            is SecurityException -> SyncFailureClass.SECURITY
            else -> SyncFailureClass.OTHER
        }
}

/** Keeps event tags and fingerprints within fixed, non-sensitive value sets. */
object SyncFailureReportSanitizer {
    private const val MAX_STACK_FRAMES = 64
    private val operations =
        setOf(
            "categories",
            "yarns",
            "projects",
            "project_create",
            "project_update",
            "project_delete",
            "project_title_image_upload",
            "project_step_image_upload",
            "project_attachment_upload",
            "project_attachment_delete",
            "project_image_delete",
            "project_title_image_order",
            "project_step_image_order",
            "yarn_create",
            "yarn_update",
            "yarn_delete",
            "yarn_photo_upload",
            "yarn_photo_delete",
            "yarn_photo_order",
        )

    fun sanitize(report: SyncFailureReport): SyncFailureReport =
        report.copy(
            operation = report.operation.takeIf(operations::contains) ?: "other",
            httpStatus = report.httpStatus.takeIf { it in 400..599 } ?: 0,
            stackTrace =
                report.stackTrace
                    .take(MAX_STACK_FRAMES)
                    .map { frame ->
                        StackTraceElement(
                            frame.className,
                            frame.methodName,
                            frame.fileName?.substringAfterLast('/')?.substringAfterLast('\\'),
                            frame.lineNumber,
                        )
                    }
                    .toList(),
        )
}

/** Removes secrets from free text before it can leave the device. */
object ErrorReportScrubber {
    private val apiToken = Regex("(?i)\\bsna_[A-Za-z0-9_-]+\\b")
    private val authorizationHeader =
        Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer|token)\\s+)[^\\s]+")
    private val bearer = Regex("(?i)(\\bbearer\\s+)[^\\s]+")
    private val secretParameter =
        Regex(
            "(?i)((?:api[-_ ]?token|token|secret|password|passwd|key|auth|cookie|session|csrf|code)" +
                "\\s*[:=]\\s*)[^\\s,;}\\]&]+"
        )
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val query = Regex("\\?[^\\s\"')]+")
    private val longToken = Regex("\\b(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]{32,}\\b")

    fun text(value: String): String =
        value
            .replace(apiToken, "[REDACTED_API_TOKEN]")
            .replace(authorizationHeader, "$1[REDACTED]")
            .replace(bearer, "$1[REDACTED]")
            .replace(secretParameter, "$1[REDACTED]")
            .replace(email, "[email]")
            .replace(query, "?[redacted]")
            .replace(longToken, "[redacted]")
}

/** Starts and stops the Sentry SDK. The real one lives in the `errorreporting` source set. */
interface ErrorReporter {
    /** Whether this build contains an SDK at all. */
    val supported: Boolean

    fun start(config: ErrorReportingConfig)

    fun stop()

    /** Reports a handled sync failure without attaching its original exception or message. */
    fun reportSyncFailure(report: SyncFailureReport)
}

/** Stands in where the build has no SDK (the Google Play bundle). */
object NoErrorReporter : ErrorReporter {
    override val supported = false

    override fun start(config: ErrorReportingConfig) = Unit

    override fun stop() = Unit

    override fun reportSyncFailure(report: SyncFailureReport) = Unit
}

/** Marks the real implementation the error-reporting source set binds, when it is in the build. */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class SentryErrorReporting

@Module
@InstallIn(SingletonComponent::class)
abstract class ErrorReporterModule {
    @BindsOptionalOf @SentryErrorReporting abstract fun optionalReporter(): ErrorReporter

    companion object {
        @Provides
        @Singleton
        fun reporter(@SentryErrorReporting optional: Optional<ErrorReporter>): ErrorReporter =
            optional.orElse(NoErrorReporter)
    }
}

/** `PATCH /api/v2/account {error_reporting_enabled}`: the per-user choice, shared with the web. */
interface ErrorReportingApi {
    @PATCH("api/v2/account")
    suspend fun setEnabled(
        @Body request: ErrorReportingRequest
    ): blue.anika.wolle.data.api.v2.V2Envelope<ErrorReportingAccountDto>
}

@Serializable
data class ErrorReportingRequest(@SerialName("error_reporting_enabled") val enabled: Boolean)

@Serializable
data class ErrorReportingAccountDto(
    @SerialName("error_reporting_enabled") val enabled: Boolean = ERROR_REPORTING_DEFAULT_ENABLED,
    @SerialName("error_reporting_available") val available: Boolean = false,
)
