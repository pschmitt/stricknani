package blue.anika.wolle.data.update

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNotificationPolicyTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(day: Int, hour: Int = 12) =
        ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun notice(
        version: String = "1.2.0",
        channel: UpdateChannel = UpdateChannel.STABLE,
        at: Long = at(1),
    ) = UpdateNotice(version, channel, at, "1.1.0+abc")

    private fun decide(
        offered: String?,
        last: UpdateNotice?,
        now: Long,
        channel: UpdateChannel = UpdateChannel.STABLE,
    ) = decideUpdateNotification(offered, channel, last, now, zone)

    @Test
    fun `first ever update is announced`() {
        assertEquals(UpdateNotificationDecision.NOTIFY, decide("1.2.0", null, at(1)))
    }

    @Test
    fun `nothing twice on the same calendar day, even for a new version`() {
        val last = notice(at = at(1, 8))
        assertEquals(UpdateNotificationDecision.NOTHING, decide("1.2.0", last, at(1, 20)))
        assertEquals(UpdateNotificationDecision.NOTHING, decide("1.3.0", last, at(1, 23)))
    }

    @Test
    fun `a new version is announced the next day`() {
        val last = notice(at = at(1, 23))
        assertEquals(UpdateNotificationDecision.NOTIFY, decide("1.3.0", last, at(2, 1)))
    }

    @Test
    fun `the same version is not repeated within the re-notify interval`() {
        val last = notice(at = at(1))
        assertEquals(UpdateNotificationDecision.NOTHING, decide("1.2.0", last, at(2)))
        assertEquals(UpdateNotificationDecision.NOTHING, decide("1.2.0", last, at(3, 13)))
    }

    @Test
    fun `the same version is repeated after the re-notify interval`() {
        val last = notice(at = at(1))
        assertEquals(UpdateNotificationDecision.NOTIFY, decide("1.2.0", last, at(4)))
        assertEquals(UpdateNotificationDecision.NOTIFY, decide("1.2.0", last, at(20)))
    }

    @Test
    fun `switching channel makes the same version a new offer`() {
        val last = notice(channel = UpdateChannel.STABLE, at = at(1))
        assertEquals(
            UpdateNotificationDecision.NOTIFY,
            decide("1.2.0", last, at(2), UpdateChannel.LATEST),
        )
    }

    @Test
    fun `the daily limit also holds across a channel switch`() {
        val last = notice(channel = UpdateChannel.STABLE, at = at(1, 8))
        assertEquals(
            UpdateNotificationDecision.NOTHING,
            decide("abc1234", last, at(1, 18), UpdateChannel.LATEST),
        )
    }

    @Test
    fun `nothing newer cancels the notification, whenever it was last shown`() {
        assertEquals(UpdateNotificationDecision.CANCEL, decide(null, null, at(1)))
        assertEquals(UpdateNotificationDecision.CANCEL, decide(null, notice(at = at(1)), at(1, 20)))
    }
}
