package blue.anika.wolle

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import blue.anika.wolle.di.MediaClient
import blue.anika.wolle.sync.SyncScheduler
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
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
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Both no-op quickly (WorkManager dedupes the periodic request; SyncWorker itself no-ops
        // pre-onboarding) so it's safe to call unconditionally on every process start.
        syncScheduler.schedulePeriodic()
        syncScheduler.syncNow()
    }

    private companion object {
        const val MAX_IMAGE_CACHE_BYTES = 256L * 1024L * 1024L
    }
}
