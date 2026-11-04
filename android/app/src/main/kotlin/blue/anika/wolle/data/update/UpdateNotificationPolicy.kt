package blue.anika.wolle.data.update

import java.time.Instant
import java.time.ZoneId

/** What was last announced: the offer ([version] on [channel]) and when, as epoch milliseconds. */
data class UpdateNotice(
    val version: String,
    val channel: UpdateChannel,
    val atMillis: Long,
    /** The running build when it was announced; a different one means the app was updated. */
    val installedBuild: String,
)

/** What a background check should do about the "update available" notification. */
enum class UpdateNotificationDecision {
    /** Post (or refresh) the notification and remember it. */
    NOTIFY,

    /** Nothing newer is offered any more: remove the notification. */
    CANCEL,

    /** Leave things as they are. */
    NOTHING,
}

/** A version already announced is announced again after this long, if still not installed. */
const val UPDATE_RENOTIFY_DAYS = 3L

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

/**
 * The rate limit of the "update available" notification, as a pure function.
 * - [offered] null means the check found nothing newer: cancel.
 * - At most one per local calendar day, whatever the version.
 * - A version (on a channel) not announced before is announced as soon as the day allows.
 * - The same version on the same channel is repeated only after [UPDATE_RENOTIFY_DAYS] days.
 *   Switching channel counts as a different offer.
 */
fun decideUpdateNotification(
    offered: String?,
    channel: UpdateChannel,
    last: UpdateNotice?,
    nowMillis: Long,
    zone: ZoneId,
): UpdateNotificationDecision {
    if (offered == null) return UpdateNotificationDecision.CANCEL
    if (last == null) return UpdateNotificationDecision.NOTIFY
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val lastDay = Instant.ofEpochMilli(last.atMillis).atZone(zone).toLocalDate()
    if (today == lastDay) return UpdateNotificationDecision.NOTHING
    val sameOffer = last.version == offered && last.channel == channel
    if (!sameOffer) return UpdateNotificationDecision.NOTIFY
    return if (nowMillis - last.atMillis >= UPDATE_RENOTIFY_DAYS * MILLIS_PER_DAY) {
        UpdateNotificationDecision.NOTIFY
    } else {
        UpdateNotificationDecision.NOTHING
    }
}
