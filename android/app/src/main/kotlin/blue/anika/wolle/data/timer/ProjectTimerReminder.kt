package blue.anika.wolle.data.timer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import blue.anika.wolle.MainActivity
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.v2.ProjectsV2Api
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.sync.canPostNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/** Reminder choices in minutes. Zero disables reminders for this project. */
object ProjectTimerReminderInterval {
    const val OFF = 0
    const val MINUTES_30 = 30
    const val HOUR_1 = 60
    const val HOURS_2 = 120
    val choices = listOf(OFF, MINUTES_30, HOUR_1, HOURS_2)
}

@Singleton
class ProjectTimerReminderRepository
@Inject
constructor(
    private val dataStore: DataStore<Preferences>,
    private val settings: SettingsRepository,
    private val capabilities: ApiCapabilities,
) {
    suspend fun currentAccountKey(): String? {
        val credentials = settings.credentials.value
        if (!credentials.isValid) return null
        val state = capabilities.resolve()
        val user = state.user ?: return null
        val value = "${credentials.serverUrl}\n${user.id}\n${state.currentTokenId ?: ""}"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }

    suspend fun interval(accountKey: String, projectId: Int): Int {
        val key = intPreferencesKey(intervalKey(accountKey, projectId))
        return dataStore.data.first()[key]?.takeIf { it in ProjectTimerReminderInterval.choices }
            ?: ProjectTimerReminderInterval.HOUR_1
    }

    suspend fun deadline(accountKey: String, projectId: Int): Long? =
        dataStore.data.first()[longPreferencesKey(deadlineKey(accountKey, projectId))]

    suspend fun scheduledStartedAt(accountKey: String, projectId: Int): String? =
        dataStore.data.first()[stringPreferencesKey(startedAtKey(accountKey, projectId))]

    suspend fun setInterval(accountKey: String, projectId: Int, interval: Int) {
        require(interval in ProjectTimerReminderInterval.choices)
        dataStore.edit { it[intPreferencesKey(intervalKey(accountKey, projectId))] = interval }
    }

    suspend fun setDeadline(
        accountKey: String,
        projectId: Int,
        deadlineMillis: Long?,
        startedAt: String? = null,
    ) {
        dataStore.edit { prefs ->
            val key = longPreferencesKey(deadlineKey(accountKey, projectId))
            val runKey = stringPreferencesKey(startedAtKey(accountKey, projectId))
            if (deadlineMillis == null) {
                prefs.remove(key)
                prefs.remove(runKey)
            } else {
                prefs[key] = deadlineMillis
                if (startedAt != null) prefs[runKey] = startedAt
            }
        }
    }

    suspend fun clearDeadlines() {
        dataStore.edit { prefs ->
            prefs
                .asMap()
                .keys
                .filter {
                    it.name.startsWith("project_timer_deadline_") ||
                        it.name.startsWith("project_timer_started_at_")
                }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    prefs.remove(key as androidx.datastore.preferences.core.Preferences.Key<Any>)
                }
        }
    }

    private fun intervalKey(accountKey: String, projectId: Int) =
        "project_timer_interval_${accountKey}_$projectId"

    private fun deadlineKey(accountKey: String, projectId: Int) =
        "project_timer_deadline_${accountKey}_$projectId"

    private fun startedAtKey(accountKey: String, projectId: Int) =
        "project_timer_started_at_${accountKey}_$projectId"
}

@Singleton
class ProjectTimerReminderScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val preferences: ProjectTimerReminderRepository,
    private val notifier: ProjectTimerReminderNotifier,
) {
    suspend fun schedule(
        projectId: Int,
        startedAt: String,
        intervalMinutes: Int,
        requestedDeadlineMillis: Long? = null,
        expectedAccountKey: String? = null,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val accountKey = preferences.currentAccountKey() ?: return
        if (expectedAccountKey != null && expectedAccountKey != accountKey) return
        if (intervalMinutes == ProjectTimerReminderInterval.OFF) {
            cancel(projectId, accountKey)
            return
        }
        val deadline =
            requestedDeadlineMillis
                ?: (System.currentTimeMillis() +
                    TimeUnit.MINUTES.toMillis(intervalMinutes.toLong()))
        preferences.setDeadline(accountKey, projectId, deadline, startedAt)
        val delayMillis = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        val request =
            OneTimeWorkRequestBuilder<ProjectTimerReminderWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    Data.Builder()
                        .putInt(ProjectTimerReminderWorker.KEY_PROJECT_ID, projectId)
                        .putString(ProjectTimerReminderWorker.KEY_STARTED_AT, startedAt)
                        .putString(ProjectTimerReminderWorker.KEY_ACCOUNT, accountKey)
                        .putLong(ProjectTimerReminderWorker.KEY_DEADLINE, deadline)
                        .putInt(ProjectTimerReminderWorker.KEY_INTERVAL, intervalMinutes)
                        .build()
                )
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniqueWork(workName(projectId), policy, request)
    }

    suspend fun cancel(projectId: Int, accountKey: String?) {
        if (accountKey != null && preferences.currentAccountKey() != accountKey) return
        workManager.cancelUniqueWork(workName(projectId))
        notifier.cancel(projectId)
        accountKey?.let { preferences.setDeadline(it, projectId, null) }
    }

    suspend fun snooze(
        projectId: Int,
        startedAt: String,
        intervalMinutes: Int,
        deadlineMillis: Long,
        expectedAccountKey: String,
    ) = schedule(projectId, startedAt, intervalMinutes, deadlineMillis, expectedAccountKey)

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
        notifier.cancelAll()
    }

    suspend fun clearSavedDeadlines() = preferences.clearDeadlines()

    private fun workName(projectId: Int) = "project_timer_reminder_$projectId"

    private companion object {
        const val TAG = "project_timer_reminders"
    }
}

@HiltWorker
class ProjectTimerReminderWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val api: ProjectsV2Api,
    private val preferences: ProjectTimerReminderRepository,
    private val appPreferences: AppPreferencesRepository,
    private val notifier: ProjectTimerReminderNotifier,
    private val scheduler: ProjectTimerReminderScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getInt(KEY_PROJECT_ID, -1)
        val expectedStartedAt = inputData.getString(KEY_STARTED_AT) ?: return Result.failure()
        val expectedAccount = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val expectedDeadline = inputData.getLong(KEY_DEADLINE, 0)
        val interval = inputData.getInt(KEY_INTERVAL, ProjectTimerReminderInterval.OFF)
        if (projectId <= 0 || interval !in ProjectTimerReminderInterval.choices || interval == 0)
            return Result.failure()
        try {
            if (!appPreferences.projectTimersEnabled.first()) {
                notifier.cancelAll()
                return Result.success()
            }
            if (preferences.currentAccountKey() != expectedAccount) return Result.success()
            if (preferences.interval(expectedAccount, projectId) != interval)
                return Result.success()
            if (preferences.deadline(expectedAccount, projectId) != expectedDeadline)
                return Result.success()
            if (preferences.scheduledStartedAt(expectedAccount, projectId) != expectedStartedAt)
                return Result.success()
            val timer = api.getProjectTimer(projectId).data
            if (!appPreferences.projectTimersEnabled.first()) {
                notifier.cancelAll()
                return Result.success()
            }
            if (timer.startedAt != expectedStartedAt) {
                preferences.setDeadline(expectedAccount, projectId, null)
                return Result.success()
            }
            val project = api.getProject(projectId).data
            if (!appPreferences.projectTimersEnabled.first()) {
                notifier.cancelAll()
                return Result.success()
            }
            if (project.access?.canEdit != true) {
                preferences.setDeadline(expectedAccount, projectId, null)
                notifier.cancel(projectId)
                return Result.success()
            }
            val latestAccount = preferences.currentAccountKey() ?: return Result.success()
            val timersEnabled = appPreferences.projectTimersEnabled.first()
            if (
                !shouldShowProjectTimerReminder(
                    featureEnabled = timersEnabled,
                    expectedAccountKey = expectedAccount,
                    currentAccountKey = latestAccount,
                    expectedStartedAt = expectedStartedAt,
                    currentStartedAt = timer.startedAt,
                    scheduledStartedAt = preferences.scheduledStartedAt(expectedAccount, projectId),
                    intervalMinutes = preferences.interval(expectedAccount, projectId),
                    deadlineMillis = preferences.deadline(expectedAccount, projectId),
                    nowMillis = System.currentTimeMillis(),
                ) || expectedDeadline != preferences.deadline(expectedAccount, projectId)
            )
                return Result.success()
            if (
                preferences.currentAccountKey() != expectedAccount ||
                    preferences.interval(expectedAccount, projectId) != interval ||
                    preferences.deadline(expectedAccount, projectId) != expectedDeadline ||
                    preferences.scheduledStartedAt(expectedAccount, projectId) != expectedStartedAt
            )
                return Result.success()
            if (!appPreferences.projectTimersEnabled.first()) {
                notifier.cancelAll()
                return Result.success()
            }
            notifier.show(projectId)
            // Remind again after another interval for as long as the same run keeps going,
            // like the web reminder that stays due until "Keep timing" or Stop.
            scheduler.schedule(
                projectId = projectId,
                startedAt = expectedStartedAt,
                intervalMinutes = interval,
                expectedAccountKey = expectedAccount,
                // Appended behind this running work: REPLACE would cancel the worker itself.
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            )
            return Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HttpException) {
            if (isGoneOrForbidden(failure.code())) {
                // The project was deleted or is no longer editable: nothing to remind about.
                preferences.setDeadline(expectedAccount, projectId, null)
                notifier.cancel(projectId)
                return Result.success()
            }
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        } catch (failure: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ACCOUNT = "account_key"
        const val KEY_DEADLINE = "deadline"
        const val KEY_INTERVAL = "interval"
    }
}

internal fun isGoneOrForbidden(httpStatus: Int): Boolean =
    httpStatus == 403 || httpStatus == 404 || httpStatus == 410

@Singleton
class ProjectTimerReminderNotifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    fun show(projectId: Int) {
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.project_timer_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply {
                    description = context.getString(R.string.project_timer_notification_description)
                }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
        val serverUrl = settings.credentials.value.serverUrl
        val projectUrl = serverUrl?.trimEnd('/')?.plus("/projects/$projectId")
        val open =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = projectUrl?.let(Uri::parse)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val pending =
            PendingIntent.getActivity(
                context,
                projectId,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_stricknani)
                .setContentTitle(context.getString(R.string.project_timer_notification_title))
                .setContentText(context.getString(R.string.project_timer_notification_text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
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
        try {
            NotificationManagerCompat.from(context)
                .notify(notificationTag(projectId), NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked after the check.
        }
    }

    fun cancel(projectId: Int) =
        NotificationManagerCompat.from(context).cancel(notificationTag(projectId), NOTIFICATION_ID)

    fun cancelAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        context
            .getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.filter { it.tag?.startsWith(TAG_PREFIX) == true }
            ?.forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
    }

    private fun notificationTag(projectId: Int) = "$TAG_PREFIX$projectId"

    private companion object {
        const val CHANNEL_ID = "project_timer_reminders"
        const val NOTIFICATION_ID = 1
        const val TAG_PREFIX = "project_timer_"
    }
}
