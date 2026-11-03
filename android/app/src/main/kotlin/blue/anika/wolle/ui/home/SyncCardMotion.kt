package blue.anika.wolle.ui.home

/** What the sync card's status badge is currently showing. */
enum class SyncBadgeMood {
    Busy,
    Pending,
    Failed,
    Done,
}

/** A one-shot flourish played when the badge changes mood. */
enum class SyncBadgeCue {
    None,
    /** A happy hop plus sparkles: a refresh just finished cleanly. */
    Hop,
    /** A short head-shake: something went wrong. */
    Shake,
}

fun syncBadgeMood(
    isRefreshing: Boolean,
    pendingChangesCount: Int,
    hasSyncFailures: Boolean,
): SyncBadgeMood =
    when {
        isRefreshing -> SyncBadgeMood.Busy
        hasSyncFailures -> SyncBadgeMood.Failed
        pendingChangesCount > 0 -> SyncBadgeMood.Pending
        else -> SyncBadgeMood.Done
    }

/**
 * The flourish for a mood change. Only a finished refresh earns the hop, so a card that simply
 * appears as "all caught up" stays calm; a failure shakes once when it first shows.
 */
fun syncBadgeCue(previous: SyncBadgeMood?, current: SyncBadgeMood): SyncBadgeCue =
    when {
        current == SyncBadgeMood.Done && previous == SyncBadgeMood.Busy -> SyncBadgeCue.Hop
        current == SyncBadgeMood.Failed && previous != SyncBadgeMood.Failed -> SyncBadgeCue.Shake
        else -> SyncBadgeCue.None
    }

/** Rotation keyframes in degrees for the failure head-shake; ends at rest. */
val SYNC_SHAKE_ANGLES = listOf(-6f, 6f, -5f, 4f, -2f, 0f)

/** Rotation keyframes in degrees for the tiny wiggle on the happy hop; ends at rest. */
val SYNC_HOP_WIGGLE_ANGLES = listOf(-8f, 6f, -3f, 0f)

/** How long the card's exit animation runs before it is removed from the list. */
const val SYNC_CARD_EXIT_MILLIS = 300

/**
 * Maps the raw pull-to-refresh fraction to how far the card is revealed. Linear up to the
 * threshold, then a light rubber band: a long pull only adds a little extra.
 */
fun syncPullReveal(fraction: Float): Float =
    when {
        fraction <= 0f -> 0f
        fraction <= 1f -> fraction
        else -> 1f + ((fraction - 1f) * 0.25f).coerceAtMost(0.2f)
    }

/** How much a card that is already on screen stretches while the user pulls (0 to 0.05). */
fun syncPullNudge(fraction: Float): Float = 0.05f * fraction.coerceIn(0f, 1f)
