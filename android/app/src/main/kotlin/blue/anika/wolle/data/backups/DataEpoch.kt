package blue.anika.wolle.data.backups

/**
 * The server's data epoch changes when an administrator restores a backup. A client that remembers
 * another epoch drops what it cached (the Room mirror, queued offline writes, sync cursors) and
 * syncs everything again; changes made on the device but not yet synced are lost with the old data.
 * The first epoch ever seen is only remembered. Mirrors `data-epoch.ts`.
 */
enum class EpochDecision {
    /** Nothing to remember or do (the server reports no epoch, or the same one). */
    UNCHANGED,
    /** First sight: remember it, discard nothing. */
    FIRST,
    /** A restore replaced the data: discard local data bound to it, then remember. */
    CHANGED,
}

fun decideEpoch(stored: String?, current: String?): EpochDecision =
    when {
        current.isNullOrEmpty() -> EpochDecision.UNCHANGED
        stored == null -> EpochDecision.FIRST
        stored == current -> EpochDecision.UNCHANGED
        else -> EpochDecision.CHANGED
    }

/** Where the last seen epoch lives (encrypted preferences; cleared by sign-out). */
interface EpochStore {
    var dataEpoch: String?
}

/**
 * Applies [decideEpoch]: on a change runs [discard] (full local wipe of account data) before the
 * new epoch is stored, so a crash in between repeats the discard instead of keeping stale data.
 * Returns the decision.
 */
class DataEpochTracker(private val store: EpochStore, private val discard: suspend () -> Unit) {
    suspend fun observe(current: String?): EpochDecision {
        val decision = decideEpoch(store.dataEpoch, current)
        if (decision == EpochDecision.CHANGED) discard()
        if (decision != EpochDecision.UNCHANGED) store.dataEpoch = current
        return decision
    }
}
