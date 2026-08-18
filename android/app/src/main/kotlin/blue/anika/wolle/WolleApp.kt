package blue.anika.wolle

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application entry point. Offline-first data/sync/DI wiring lands in SNA-6/SNA-7 - this is
 * deliberately minimal for now (SNA-5: repo scaffold + Compose shell).
 */
@HiltAndroidApp
class WolleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
