package blue.anika.wolle

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import blue.anika.wolle.crash.CrashReportInstaller
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.update.UpdateScheduler
import blue.anika.wolle.data.update.inAppUpdateAvailable
import blue.anika.wolle.di.MediaClient
import blue.anika.wolle.sync.SyncNotifier
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.NetworkStatus
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber

/**
 * Application entry point. `Configuration.Provider` lets [SyncScheduler]'s enqueued
 * [blue.anika.wolle.sync.SyncWorker] resolve its `@AssistedInject` dependencies through Hilt;
 * `SingletonImageLoader.Factory` wires Coil to [MediaClient] rather than the app's main OkHttp
 * client - see that qualifier's kdoc (`di/NetworkModule.kt`) for why.
 */
@HiltAndroidApp
class StricknaniApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var syncNotifier: SyncNotifier
    @Inject lateinit var updateScheduler: UpdateScheduler
    @Inject lateinit var appPreferences: AppPreferencesRepository
    @Inject @MediaClient lateinit var mediaOkHttpClient: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { mediaOkHttpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(MAX_IMAGE_CACHE_BYTES)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashReportInstaller.install(this)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        NetworkStatus.start(this)
        syncNotifier.ensureChannel()
        // Both no-op quickly (WorkManager dedupes the periodic request; SyncWorker itself no-ops
        // pre-onboarding) so it's safe to call unconditionally on every process start.
        syncScheduler.schedulePeriodic()
        syncScheduler.syncNow()
        if (inAppUpdateAvailable) {
            // Keeps the periodic update check in line with "Update automatically" after a restart.
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                updateScheduler.setAutoCheck(appPreferences.autoUpdate.first())
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_CACHE_BYTES = 256L * 1024L * 1024L
    }
}
