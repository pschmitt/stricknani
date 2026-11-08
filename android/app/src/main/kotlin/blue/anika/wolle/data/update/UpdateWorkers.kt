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
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** In-app updating exists in the GitHub APK builds (release and debug), not in Play builds. */
val inAppUpdateAvailable: Boolean
    get() = BuildConfig.SELF_UPDATE_ENABLED

/**
 * Background checks, notifications and auto-update exist in release APKs from GitHub only. A debug
 * build is deployed by hand or by CI and must not be replaced behind its owner's back.
 */
val backgroundUpdatesAvailable: Boolean
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
            val progress = launch {
                controller.state.collect { notifier.render(it, update.version) }
            }
            try {
                controller.execute(update)
            } finally {
                progress.cancel()
            }
        }
        notifier.render(controller.state.value, update.version)
        return Result.success()
    }

    companion object {
        const val KEY_AUTOMATIC = "automatic"
    }
}

/**
 * The one periodic update check, about once a day. It queues the install when "Update
 * automatically" is on and a silent path exists, and otherwise announces the new version in a
 * rate-limited notification when "Notify me about app updates" is on. Like every periodic job it
 * sits out while the battery saver is on.
 */
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
    private val availableNotifier: UpdateAvailableNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!backgroundUpdatesAvailable) return Result.success()
        val auto = preferences.autoUpdate.first()
        val notify = preferences.notifyAppUpdates.first()
        if (!auto && !notify) return Result.success()
        if (shouldSkipBackgroundWork(true, applicationContext.isPowerSaveMode())) {
            return Result.success()
        }
        if (controller.state.value.isBusy) return Result.success()
        val channel = preferences.updateChannel.first()
        val result = checker.checkCurrentBuild(channel)
        val update = (result as? UpdateCheckResult.Available)?.update
        // Without a silent path every update would need a tap; the notification is the way to say
        // so.
        if (
            auto && update != null && update.canInstallInApp() && resolver.silentInstallPossible()
        ) {
            scheduler.enqueueAutomatic(update)
            return Result.success()
        }
        if (notify) announce(result, channel)
        return Result.success()
    }

    private suspend fun announce(result: UpdateCheckResult, channel: UpdateChannel) {
        val offered =
            when (result) {
                is UpdateCheckResult.Available -> result.update.version
                UpdateCheckResult.UpToDate,
                UpdateCheckResult.AheadOfStable -> null
                // No answer (offline, rate limited, unknown build): keep what is shown.
                UpdateCheckResult.NotEligible,
                UpdateCheckResult.Failed -> return
            }
        val now = System.currentTimeMillis()
        val decision =
            decideUpdateNotification(
                offered,
                channel,
                preferences.updateNotice.first(),
                now,
                ZoneId.systemDefault(),
            )
        when (decision) {
            UpdateNotificationDecision.CANCEL -> {
                availableNotifier.cancel()
                preferences.clearUpdateNotice()
            }
            UpdateNotificationDecision.NOTIFY ->
                // Only a notification that was actually shown counts against the limit.
                if (
                    availableNotifier.show(
                        requireNotNull(offered),
                        (result as? UpdateCheckResult.Available)?.update?.releaseNotes,
                    )
                ) {
                    preferences.recordUpdateNotice(
                        UpdateNotice(offered, channel, now, installedBuildId())
                    )
                }
            UpdateNotificationDecision.NOTHING -> Unit
        }
    }
}

/** Identifies the running build, so a notice can be told apart after the app was updated. */
fun installedBuildId(): String = "${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_REVISION}"

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

    /**
     * Keeps the one periodic check in line with the two settings that need it: it runs when either
     * "Update automatically" or "Notify me about app updates" is on, never outside release APKs
     * from GitHub.
     */
    fun syncChecks(autoUpdate: Boolean, notify: Boolean) {
        if (!backgroundUpdatesAvailable || !(autoUpdate || notify)) {
            workManager.cancelUniqueWork(CHECK_WORK_NAME)
            workManager.cancelUniqueWork(AUTO_WORK_NAME)
            return
        }
        if (!autoUpdate) workManager.cancelUniqueWork(AUTO_WORK_NAME)
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
        private const val CHECK_INTERVAL_HOURS = 24L

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
