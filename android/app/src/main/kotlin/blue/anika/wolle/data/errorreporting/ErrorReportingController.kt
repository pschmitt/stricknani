package blue.anika.wolle.data.errorreporting

import android.util.Log
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the Account settings switch needs. */
data class ErrorReportingUiState(val visible: Boolean = false, val enabled: Boolean = false)

/**
 * Starts and stops the Sentry SDK to match what the server offers and what the user chose.
 *
 * Reporting runs only when this build includes the SDK, the server reports it as available, and
 * both the account's server-side choice (shared with the web app) and this device's switch are on.
 * Turning it off stops the SDK at once, before anything else, even when the server cannot be
 * reached to record the choice.
 */
@Singleton
class ErrorReportingController
@Inject
constructor(
    private val reporter: ErrorReporter,
    private val preferences: AppPreferencesRepository,
    private val settings: SettingsRepository,
    private val metaApi: MetaV2Api,
    private val accountApi: ErrorReportingApi,
) {
    private val mutex = Mutex()

    val state: Flow<ErrorReportingUiState> =
        combine(preferences.errorReportingEnabled, preferences.errorReportingServer) { local, meta
            ->
            ErrorReportingUiState(
                visible =
                    BuildConfig.ERROR_REPORTING_COMPILED &&
                        reporter.supported &&
                        meta?.available == true,
                enabled = ErrorReportingGate.switchOn(meta, local),
            )
        }

    /** Follows sign-in, sign-out and server changes for the life of the process. */
    suspend fun run() {
        if (!reporter.supported) return
        settings.credentials
            .map { it.isValid to it.serverUrl }
            .distinctUntilChanged()
            .collectLatest { sync() }
    }

    /** Re-reads what the server says (keeping the saved answer offline), then applies it. */
    suspend fun sync() {
        mutex.withLock {
            if (!settings.credentials.value.isValid) {
                reporter.stop()
                return
            }
            try {
                val meta = metaApi.getMeta().data
                preferences.setErrorReportingServer(meta.errorReporting ?: ErrorReportingDto())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Offline or an old server: keep the last known answer.
            }
            applyLocked()
        }
    }

    /**
     * Reapplies the cached consent/configuration before reporting, including during startup races.
     */
    suspend fun reportSyncFailure(report: SyncFailureReport) {
        // Being offline is normal for an offline-first app; the sync retries later.
        if (!report.failureClass.reportable) return
        if (!reporter.supported) {
            Log.i("SyncReplay", "Sync failure reporting unavailable: SDK not included")
            return
        }
        try {
            sync()
            reporter.reportSyncFailure(SyncFailureReportSanitizer.sanitize(report))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Reporting is best-effort and must not change the result of the sync operation.
            Log.w(
                "SyncReplay",
                "Sync failure report could not be queued: cause=${SyncFailureClassifier.classify(failure).tagValue}",
            )
        }
    }

    /** The user's switch. Returns false when the server could not record it (the device obeys). */
    suspend fun setEnabled(enabled: Boolean): Boolean = mutex.withLock {
        preferences.setErrorReportingEnabled(enabled)
        applyLocked()
        try {
            val saved = accountApi.setEnabled(ErrorReportingRequest(enabled)).data
            val current = preferences.errorReportingServer.first() ?: ErrorReportingDto()
            preferences.setErrorReportingServer(
                current.copy(enabled = saved.enabled, available = saved.available)
            )
            applyLocked()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            false
        }
    }

    private suspend fun applyLocked() {
        val config =
            ErrorReportingGate.config(
                compiledIn = BuildConfig.ERROR_REPORTING_COMPILED,
                meta = preferences.errorReportingServer.first(),
                serverUrl = settings.credentials.value.serverUrl,
                localEnabled = preferences.errorReportingEnabled.first(),
            )
        if (config != null) reporter.start(config) else reporter.stop()
    }
}
