package blue.anika.wolle.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.sync.isPowerSaveMode
import blue.anika.wolle.sync.shouldSkipBackgroundWork
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** In-app updating exists in release APKs from GitHub only: not in Play builds, not in debug. */
val inAppUpdateAvailable: Boolean
    get() = BuildConfig.SELF_UPDATE_ENABLED && !BuildConfig.DEBUG

/** True when the in-app installer can take this update (an APK and checksums exist). */
fun AppUpdate.canInstallInApp(): Boolean =
    inAppUpdateAvailable && asset != null && checksumsUrl != null

/** Runs an update in the background so leaving the screen does not stop the download. */
@HiltWorker
class ApkUpdateWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val controller: UpdateInstallController,
    private val notifier: UpdateNotifier,
    private val resolver: InstallPathResolver,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!inAppUpdateAvailable) return Result.failure()
        val update = UpdateScheduler.decode(inputData) ?: return Result.failure()
        val automatic = inputData.getBoolean(KEY_AUTOMATIC, false)
        // The automatic path only ever runs when nothing needs the user, and never on battery
        // saver; the constraints were checked at enqueue time, the device state may have changed.
        if (automatic) {
            if (shouldSkipBackgroundWork(true, applicationContext.isPowerSaveMode())) {
                return Result.success()
            }
            if (!resolver.silentInstallPossible()) {
                return Result.success()
            }
            controller.markQueued()
        }
        coroutineScope {
            val progress = launch { controller.state.collect(notifier::render) }
            try {
                controller.execute(update)
            } finally {
                progress.cancel()
            }
        }
        notifier.render(controller.state.value)
        return Result.success()
    }

    companion object {
        const val KEY_AUTOMATIC = "automatic"
    }
}

/** Periodic check behind "Update automatically": finds a newer build and queues its install. */
@HiltWorker
class UpdateCheckWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: UpdateChecker,
    private val preferences: AppPreferencesRepository,
    private val controller: UpdateInstallController,
    private val scheduler: UpdateScheduler,
    private val resolver: InstallPathResolver,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!inAppUpdateAvailable || !preferences.autoUpdate.first()) return Result.success()
        if (shouldSkipBackgroundWork(true, applicationContext.isPowerSaveMode())) {
            return Result.success()
        }
        // Without a silent path every update would need a tap; leave those to the user.
        if (!resolver.silentInstallPossible()) return Result.success()
        if (controller.state.value.isBusy) return Result.success()
        val update =
            (checker.checkCurrentBuild(preferences.updateChannel.first())
                    as? UpdateCheckResult.Available)
                ?.update ?: return Result.success()
        if (update.canInstallInApp()) scheduler.enqueueAutomatic(update)
        return Result.success()
    }
}

@Singleton
class UpdateScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val controller: UpdateInstallController,
) {

    /** The user asked for the update: start now. */
    fun startInstall(update: AppUpdate) {
        if (!update.canInstallInApp()) return
        controller.markQueued()
        val request =
            OneTimeWorkRequestBuilder<ApkUpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(encode(update, automatic = false))
                .build()
        workManager.enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Waits for a charging, idle device on an unmetered-or-not network, then installs. */
    fun enqueueAutomatic(update: AppUpdate) {
        val request =
            OneTimeWorkRequestBuilder<ApkUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(encode(update, automatic = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniqueWork(AUTO_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Turns the periodic background check on or off (always off outside in-app builds). */
    fun setAutoCheck(enabled: Boolean) {
        if (!enabled || !inAppUpdateAvailable) {
            workManager.cancelUniqueWork(CHECK_WORK_NAME)
            workManager.cancelUniqueWork(AUTO_WORK_NAME)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        workManager.enqueueUniquePeriodicWork(
            CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    internal companion object {
        private const val MANUAL_WORK_NAME = "stricknani_app_update"
        private const val AUTO_WORK_NAME = "stricknani_app_update_auto"
        private const val CHECK_WORK_NAME = "stricknani_app_update_check"
        private const val CHECK_INTERVAL_HOURS = 12L

        fun encode(update: AppUpdate, automatic: Boolean): Data {
            val asset = requireNotNull(update.asset)
            return Data.Builder()
                .putString("version", update.version)
                .putString("downloadUrl", update.downloadUrl)
                .putString("assetName", asset.name)
                .putString("assetUrl", asset.url)
                .putLong("assetSize", asset.sizeBytes)
                .putString("checksumsUrl", update.checksumsUrl)
                .putString("stableVersion", update.stableVersion)
                .putBoolean(ApkUpdateWorker.KEY_AUTOMATIC, automatic)
                .build()
        }

        fun decode(data: Data): AppUpdate? {
            val version = data.getString("version") ?: return null
            val name = data.getString("assetName") ?: return null
            val url = data.getString("assetUrl") ?: return null
            return AppUpdate(
                version = version,
                downloadUrl = data.getString("downloadUrl") ?: url,
                asset = UpdateAsset(name, url, data.getLong("assetSize", 0)),
                checksumsUrl = data.getString("checksumsUrl"),
                stableVersion = data.getString("stableVersion"),
            )
        }
    }
}
