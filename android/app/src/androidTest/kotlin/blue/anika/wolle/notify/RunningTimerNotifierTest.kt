package blue.anika.wolle.notify

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.data.timer.RunningTimerNotification
import blue.anika.wolle.data.timer.RunningTimerNotifier
import blue.anika.wolle.sync.RustReplayTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The running-timer notification is ongoing, silent, clickable and has a Stop action. */
@RunWith(AndroidJUnit4::class)
class RunningTimerNotifierTest {
    @Test
    fun builds_an_ongoing_clickable_notification_with_a_stop_action() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings =
            EntryPointAccessors.fromApplication(context, RustReplayTestEntryPoint::class.java)
                .settingsRepository()
        val notifier = RunningTimerNotifier(context, settings)
        notifier.ensureChannel()
        val notification =
            notifier.build(RunningTimerNotification(7, "Socks", 1_000_000L, "account"))

        assertEquals("Socks", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(1_000_000L, notification.`when`)
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.deleteIntent)
        assertEquals(1, notification.actions.size)
        assertNotNull(notification.actions[0].actionIntent)
        val channel =
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(RunningTimerNotifier.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }
}
