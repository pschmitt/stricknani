package blue.anika.wolle.data.impersonation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** The server side of an impersonation. */
interface ImpersonationBackend {
    /** Asks, with the administrator's own credential, for a credential acting as [targetId]. */
    suspend fun start(targetId: Long): StartedImpersonation

    /** Revokes the credential this process sends (the impersonation's). */
    suspend fun stop()
}

class StartedImpersonation(val token: String, val session: ImpersonationSession)

/** Where the impersonation is persisted. Writes are synchronous: the process restarts right after. */
interface ImpersonationStore {
    /** The impersonation this process runs as; fixed for the life of the process. */
    val session: ImpersonationSession?

    /** Stores [token] and [session] so the next process start runs as the impersonation. */
    fun begin(session: ImpersonationSession, token: String): Boolean

    /** Forgets the impersonation, so the next process start is the administrator's own. */
    fun end(): Boolean
}

/** Deletes what an impersonation left on disk (its database and queued uploads). */
fun interface ImpersonationDataWiper {
    fun wipe(): Boolean
}

/** Restarts the app's process; [afterImpersonation] returns to the administrator's account list. */
fun interface AppRestarter {
    fun restart(afterImpersonation: Boolean)
}

sealed interface StartResult {
    /** Persisted; the app restarts into the impersonation. */
    data object Started : StartResult

    data object AlreadyImpersonating : StartResult

    data class Failed(val cause: Throwable) : StartResult
}

/**
 * Switches the app between the administrator's own account and an impersonation of another one.
 *
 * Isolation comes from the process, not from clearing state: the identity (credential, Room file,
 * upload queue) is chosen once when the process starts, and both [start] and [stop] end with a
 * restart. While the old process winds down it keeps the identity it had, so the administrator's
 * queued writes can never be replayed with the impersonation's credential, nor the other way round.
 */
class ImpersonationController(
    private val store: ImpersonationStore,
    private val backend: ImpersonationBackend,
    private val wiper: ImpersonationDataWiper,
    private val restarter: AppRestarter,
    private val stopTimeoutMillis: Long = STOP_TIMEOUT_MILLIS,
) {
    val session: ImpersonationSession?
        get() = store.session

    val isImpersonating: Boolean
        get() = store.session != null

    suspend fun start(targetId: Long): StartResult {
        if (isImpersonating) return StartResult.AlreadyImpersonating
        val started =
            try {
                backend.start(targetId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return StartResult.Failed(failure)
            }
        if (!store.begin(started.session, started.token)) {
            // Not stored: nothing will use the credential, so do not leave it alive.
            revokeQuietly()
            return StartResult.Failed(IllegalStateException("Could not store the impersonation"))
        }
        restarter.restart(afterImpersonation = false)
        return StartResult.Started
    }

    /**
     * Ends the impersonation and returns to the administrator's own account. The server revocation
     * is best effort (offline, or already expired): the credential also dies on its own after an
     * hour and cannot do more than the impersonation could. The local end does not wait for it.
     */
    suspend fun stop(): Boolean {
        if (!isImpersonating) return false
        revokeQuietly()
        if (!store.end()) return false
        // The next start wipes leftovers again, so a failure here is not fatal.
        wiper.wipe()
        restarter.restart(afterImpersonation = true)
        return true
    }

    private suspend fun revokeQuietly() {
        try {
            withTimeoutOrNull(stopTimeoutMillis) { backend.stop() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {}
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
