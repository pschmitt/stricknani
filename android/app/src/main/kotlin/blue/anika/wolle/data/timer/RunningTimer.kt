package blue.anika.wolle.data.timer

import blue.anika.wolle.data.api.v2.ProjectTimerDto
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import retrofit2.HttpException

/** One project timer that has a persistent "running" notification. */
data class RunningTimerNotification(
    val projectId: Int,
    /** The project's name, shown as the title. */
    val projectName: String,
    /**
     * The instant the chronometer counts from: now minus the project's accumulated total, so the
     * notification shows the same time as the project's stopwatch ("Time spent on this project").
     */
    val baseMillis: Long,
    /** The account the timer belongs to; the stop action refuses to act for another account. */
    val accountKey: String,
)

/** What a project's timer notification remembers across process death and reboots. */
@Serializable data class RegisteredTimer(val name: String, val dismissed: Boolean = false)

const val RUNNING_TIMER_TAG_PREFIX = "running_timer_"

/** The persistent notification's tag: one notification per running timer, keyed by project. */
fun runningTimerTag(projectId: Int) = "$RUNNING_TIMER_TAG_PREFIX$projectId"

/** The chronometer base for a timer whose total (including the running part) is known now. */
fun runningTimerBaseMillis(nowMillis: Long, totalSeconds: Long): Long =
    nowMillis - totalSeconds.coerceAtLeast(0) * 1_000L

/**
 * True when the project's timer deserves a persistent notification: the feature is on for this
 * device, the timer runs, and this user may stop it (viewers of a shared timer get none, as they
 * get no reminders).
 */
fun shouldShowRunningTimer(
    featureEnabled: Boolean,
    canEdit: Boolean,
    timer: ProjectTimerDto,
): Boolean = featureEnabled && canEdit && timer.startedAt != null

/** Where the registry of running timers lives (DataStore in the app, a map in tests). */
interface RunningTimerRegistry {
    suspend fun all(accountKey: String): Map<Int, RegisteredTimer>

    suspend fun put(accountKey: String, projectId: Int, timer: RegisteredTimer)

    suspend fun remove(accountKey: String, projectId: Int)

    suspend fun clear()
}

/** Reads and stops project timers (the v2 API in the app, a fake in tests). */
interface ProjectTimerGateway {
    suspend fun get(projectId: Int): ProjectTimerDto

    suspend fun stop(projectId: Int): ProjectTimerDto
}

/** Posts and removes the notifications (the system in the app, a recorder in tests). */
interface RunningTimerSink {
    fun show(notification: RunningTimerNotification)

    fun cancel(projectId: Int)

    fun cancelAll()
}

enum class StopTimerResult {
    /** The timer is stopped (or was already) and its notification is gone. */
    Stopped,
    /**
     * The timer cannot be stopped by this user any more (gone, forbidden); notification removed.
     */
    Gone,
    /** Another account is signed in, or the feature is off: nothing was done. */
    Ignored,
    /** The network failed; the notification stays and the stop should be retried. */
    Retry,
}

/**
 * The logic behind the persistent running-timer notifications, free of Android classes. Fed by the
 * project screen ([onTimerObserved]), a periodic check ([refreshAll], which also re-posts after a
 * reboot) and the notification's Stop action ([stop]).
 */
class RunningTimerController(
    private val gateway: ProjectTimerGateway,
    private val registry: RunningTimerRegistry,
    private val sink: RunningTimerSink,
    private val timersEnabled: suspend () -> Boolean,
    private val accountKey: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _stopped = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** Project ids whose timer was stopped from the notification: open screens reload. */
    val stopped: SharedFlow<Int> = _stopped.asSharedFlow()

    /** The app read the timer of a project (opened it, started or stopped it). */
    suspend fun onTimerObserved(
        projectId: Int,
        projectName: String,
        canEdit: Boolean,
        timer: ProjectTimerDto,
    ) {
        val account = accountKey() ?: return
        if (shouldShowRunningTimer(timersEnabled(), canEdit, timer)) {
            // A fresh look at a running timer brings back a notification the user swiped away.
            registry.put(account, projectId, RegisteredTimer(projectName))
            sink.show(notificationOf(projectId, projectName, timer, account))
        } else {
            registry.remove(account, projectId)
            sink.cancel(projectId)
        }
    }

    /**
     * Checks every remembered timer against the server: still running -> (re-)post, stopped
     * elsewhere or gone -> remove. A network failure leaves the notification as it is. Returns how
     * many timers are still running, so the caller can stop its periodic check at zero.
     */
    suspend fun refreshAll(): Int {
        val account = accountKey() ?: return 0
        if (!timersEnabled()) {
            clearAll()
            return 0
        }
        var running = 0
        for ((projectId, entry) in registry.all(account)) {
            try {
                val timer = gateway.get(projectId)
                if (timer.startedAt == null) {
                    registry.remove(account, projectId)
                    sink.cancel(projectId)
                } else {
                    running++
                    // A notification the user dismissed stays away until the app sees the timer
                    // again; a reboot (no dismissal recorded) brings it back.
                    if (!entry.dismissed)
                        sink.show(notificationOf(projectId, entry.name, timer, account))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: HttpException) {
                if (isGoneOrForbidden(failure.code())) {
                    registry.remove(account, projectId)
                    sink.cancel(projectId)
                } else running++
            } catch (failure: IOException) {
                running++
            }
        }
        return running
    }

    /** The notification's Stop action: stops the timer exactly like the project screen's button. */
    suspend fun stop(projectId: Int, expectedAccountKey: String): StopTimerResult {
        val account = accountKey()
        if (account == null || account != expectedAccountKey || !timersEnabled())
            return StopTimerResult.Ignored
        return try {
            gateway.stop(projectId)
            registry.remove(account, projectId)
            sink.cancel(projectId)
            _stopped.tryEmit(projectId)
            StopTimerResult.Stopped
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HttpException) {
            if (isGoneOrForbidden(failure.code())) {
                registry.remove(account, projectId)
                sink.cancel(projectId)
                _stopped.tryEmit(projectId)
                StopTimerResult.Gone
            } else StopTimerResult.Retry
        } catch (failure: IOException) {
            StopTimerResult.Retry
        }
    }

    /** The user swiped the notification away: do not re-post it on every check. */
    suspend fun onDismissed(projectId: Int) {
        val account = accountKey() ?: return
        val entry = registry.all(account)[projectId] ?: return
        registry.put(account, projectId, entry.copy(dismissed = true))
    }

    /** Timers turned off for this device, or signed out: no notification, nothing remembered. */
    suspend fun clearAll() {
        sink.cancelAll()
        registry.clear()
    }

    /** Whether any timer of the current account is remembered as running. */
    suspend fun hasRunning(): Boolean = accountKey()?.let { registry.all(it).isNotEmpty() } ?: false

    private fun notificationOf(
        projectId: Int,
        name: String,
        timer: ProjectTimerDto,
        account: String,
    ) =
        RunningTimerNotification(
            projectId = projectId,
            projectName = name,
            baseMillis = runningTimerBaseMillis(now(), timer.totalSeconds),
            accountKey = account,
        )
}
