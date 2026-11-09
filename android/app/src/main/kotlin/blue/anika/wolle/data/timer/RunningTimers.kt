package blue.anika.wolle.data.timer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.data.api.v2.ProjectTimerDto
import blue.anika.wolle.data.api.v2.ProjectTimerRequest
import blue.anika.wolle.data.api.v2.ProjectsV2Api
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.notify.applyBrand
import blue.anika.wolle.sync.canPostNotifications
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private const val REGISTRY_KEY_PREFIX = "running_timers_"

/** The running timers, remembered in the app's DataStore so they survive process death. */
@Singleton
class DataStoreRunningTimerRegistry
@Inject
constructor(private val dataStore: DataStore<Preferences>) : RunningTimerRegistry {
    private val serializer = MapSerializer(Int.serializer(), RegisteredTimer.serializer())

    private fun key(accountKey: String) = stringPreferencesKey("$REGISTRY_KEY_PREFIX$accountKey")

    override suspend fun all(accountKey: String): Map<Int, RegisteredTimer> =
        dataStore.data.first()[key(accountKey)]?.let {
            runCatching { Json.decodeFromString(serializer, it) }.getOrNull()
        } ?: emptyMap()

    override suspend fun put(accountKey: String, projectId: Int, timer: RegisteredTimer) =
        update(accountKey) { it + (projectId to timer) }

    override suspend fun remove(accountKey: String, projectId: Int) =
        update(accountKey) { it - projectId }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs
                .asMap()
                .keys
                .filter { it.name.startsWith(REGISTRY_KEY_PREFIX) }
                .forEach { prefs.remove(it) }
        }
    }

    private suspend fun update(
        accountKey: String,
        change: (Map<Int, RegisteredTimer>) -> Map<Int, RegisteredTimer>,
    ) {
        dataStore.edit { prefs ->
            val current =
                prefs[key(accountKey)]?.let {
                    runCatching { Json.decodeFromString(serializer, it) }.getOrNull()
                } ?: emptyMap()
            val next = change(current)
            if (next.isEmpty()) prefs.remove(key(accountKey))
            else prefs[key(accountKey)] = Json.encodeToString(serializer, next)
        }
    }
}

/** The stop goes through the same v2 route as the project screen's Stop button. */
@Singleton
class ApiProjectTimerGateway @Inject constructor(private val api: ProjectsV2Api) :
    ProjectTimerGateway {
    override suspend fun get(projectId: Int): ProjectTimerDto = api.getProjectTimer(projectId).data

    override suspend fun stop(projectId: Int): ProjectTimerDto =
        api.updateProjectTimer(projectId, ProjectTimerRequest("stop")).data
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RunningTimerModule {
    @Binds abstract fun bindRegistry(impl: DataStoreRunningTimerRegistry): RunningTimerRegistry

    @Binds abstract fun bindGateway(impl: ApiProjectTimerGateway): ProjectTimerGateway

    @Binds abstract fun bindSink(impl: RunningTimerNotifier): RunningTimerSink
}

/**
 * The ongoing notification of a running project timer: the project's name, a chronometer that ticks
 * without updates, a Stop action, and a tap that opens the project. Silent (low importance
 * channel), one per project.
 */
@Singleton
class RunningTimerNotifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : RunningTimerSink {
    /** Safe on every process start: creating a channel again changes nothing. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.running_timer_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = context.getString(R.string.running_timer_notification_description)
                    setSound(null, null)
                    enableVibration(false)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    override fun show(notification: RunningTimerNotification) {
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        if (
            !canPostNotifications(
                Build.VERSION.SDK_INT,
                granted,
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
            )
        )
            return
        ensureChannel()
        try {
            NotificationManagerCompat.from(context)
                .notify(
                    runningTimerTag(notification.projectId),
                    NOTIFICATION_ID,
                    build(notification),
                )
        } catch (_: SecurityException) {
            // Permission can be revoked after the check.
        }
    }

    internal fun build(notification: RunningTimerNotification): android.app.Notification {
        val projectId = notification.projectId
        val projectUrl =
            settings.credentials.value.serverUrl?.trimEnd('/')?.plus("/projects/$projectId")
        val open =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = projectUrl?.let(Uri::parse)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stricknani)
            .setContentTitle(
                notification.projectName.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.project_timer_title)
            )
            .setContentText(context.getString(R.string.project_timer_running_caption))
            .setContentIntent(PendingIntent.getActivity(context, projectId, open, flags))
            .addAction(
                0,
                context.getString(R.string.project_timer_stop),
                PendingIntent.getBroadcast(
                    context,
                    projectId,
                    RunningTimerReceiver.stopIntent(context, projectId, notification.accountKey),
                    flags,
                ),
            )
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    projectId,
                    RunningTimerReceiver.dismissedIntent(context, projectId),
                    flags,
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .applyBrand(
                context,
                context.getString(R.string.project_timer_notification_subtext),
                whenMillis = notification.baseMillis,
            )
            .setUsesChronometer(true)
            .build()
    }

    override fun cancel(projectId: Int) =
        NotificationManagerCompat.from(context).cancel(runningTimerTag(projectId), NOTIFICATION_ID)

    override fun cancelAll() {
        context
            .getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.filter { it.tag?.startsWith(RUNNING_TIMER_TAG_PREFIX) == true }
            ?.forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
    }

    companion object {
        const val CHANNEL_ID = "running_timers"
        const val NOTIFICATION_ID = 2
    }
}

/**
 * The app-wide entry to the running-timer notifications. A plain ongoing notification, re-posted by
 * a small periodic check, is enough: the timer is state on the server, so nothing has to run in the
 * background between checks, and a foreground service would only add a second, permanent
 * notification and Play declarations.
 */
@Singleton
class RunningTimers
@Inject
constructor(
    private val workManager: WorkManager,
    private val settings: SettingsRepository,
    private val reminderPreferences: ProjectTimerReminderRepository,
    private val reminderScheduler: ProjectTimerReminderScheduler,
    appPreferences: AppPreferencesRepository,
    gateway: ProjectTimerGateway,
    registry: RunningTimerRegistry,
    sink: RunningTimerSink,
) {
    private val controller =
        RunningTimerController(
            gateway = gateway,
            registry = registry,
            sink = sink,
            timersEnabled = { appPreferences.projectTimersEnabled.first() },
            accountKey = { reminderPreferences.currentAccountKey() },
        )

    /** Emits the id of a project whose timer the notification just stopped. */
    val stopped: SharedFlow<Int> = controller.stopped

    suspend fun onTimerObserved(
        projectId: Int,
        projectName: String,
        canEdit: Boolean,
        timer: ProjectTimerDto,
    ) {
        controller.onTimerObserved(projectId, projectName, canEdit, timer)
        syncSchedule()
    }

    /** App start, the periodic check: re-post, drop what stopped elsewhere. */
    suspend fun refreshAll() {
        controller.refreshAll()
        syncSchedule()
    }

    suspend fun stop(projectId: Int, accountKey: String): StopTimerResult {
        val result = controller.stop(projectId, accountKey)
        if (result == StopTimerResult.Stopped || result == StopTimerResult.Gone)
            reminderScheduler.cancel(projectId, accountKey)
        syncSchedule()
        return result
    }

    suspend fun onDismissed(projectId: Int) = controller.onDismissed(projectId)

    suspend fun clearAll() {
        controller.clearAll()
        syncSchedule()
    }

    /** Queues a stop for when the network is back (the notification stays meanwhile). */
    fun retryStopWhenOnline(projectId: Int, accountKey: String) {
        val request =
            OneTimeWorkRequestBuilder<RunningTimerStopWorker>()
                .setConstraints(networkConstraints())
                .setInputData(
                    Data.Builder()
                        .putInt(RunningTimerStopWorker.KEY_PROJECT_ID, projectId)
                        .putString(RunningTimerStopWorker.KEY_ACCOUNT, accountKey)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        workManager.enqueueUniqueWork(
            "running_timer_stop_$projectId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** The local server on this phone needs no network; a remote server does. */
    private fun networkConstraints() =
        if (settings.isLocalMode) Constraints.NONE
        else Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The periodic check exists exactly while a timer is remembered as running. */
    private suspend fun syncSchedule() {
        if (controller.hasRunning()) {
            val request =
                PeriodicWorkRequestBuilder<RunningTimerCheckWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(networkConstraints())
                    .build()
            workManager.enqueueUniquePeriodicWork(
                CHECK_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } else {
            workManager.cancelUniqueWork(CHECK_WORK)
        }
    }

    private companion object {
        const val CHECK_WORK = "running_timer_check"
    }
}

@HiltWorker
class RunningTimerCheckWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runningTimers: RunningTimers,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            runningTimers.refreshAll()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return Result.retry()
        }
        return Result.success()
    }
}

@HiltWorker
class RunningTimerStopWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runningTimers: RunningTimers,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getInt(KEY_PROJECT_ID, -1)
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        if (projectId <= 0) return Result.failure()
        return if (runningTimers.stop(projectId, account) == StopTimerResult.Retry) Result.retry()
        else Result.success()
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_ACCOUNT = "account_key"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RunningTimerEntryPoint {
    fun runningTimers(): RunningTimers
}

/**
 * Handles the notification's Stop button (without opening the app) and its dismissal. The stop runs
 * right away; if the network fails the notification stays and a worker retries once online.
 */
class RunningTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val projectId = intent.getIntExtra(EXTRA_PROJECT_ID, -1)
        if (projectId <= 0) return
        val runningTimers =
            EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    RunningTimerEntryPoint::class.java,
                )
                .runningTimers()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_STOP -> {
                        val account = intent.getStringExtra(EXTRA_ACCOUNT) ?: return@launch
                        val result =
                            try {
                                runningTimers.stop(projectId, account)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                StopTimerResult.Retry
                            }
                        if (result == StopTimerResult.Retry)
                            runningTimers.retryStopWhenOnline(projectId, account)
                    }
                    ACTION_DISMISSED -> runningTimers.onDismissed(projectId)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Never crash the app from a notification button.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "blue.anika.wolle.action.STOP_RUNNING_TIMER"
        const val ACTION_DISMISSED = "blue.anika.wolle.action.RUNNING_TIMER_DISMISSED"
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_ACCOUNT = "account_key"

        fun stopIntent(context: Context, projectId: Int, accountKey: String) =
            Intent(context, RunningTimerReceiver::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_ACCOUNT, accountKey)

        fun dismissedIntent(context: Context, projectId: Int) =
            Intent(context, RunningTimerReceiver::class.java)
                .setAction(ACTION_DISMISSED)
                .putExtra(EXTRA_PROJECT_ID, projectId)
    }
}
