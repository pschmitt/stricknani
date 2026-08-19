package blue.anika.wolle.sync

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import blue.anika.wolle.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncNotifierTest {
    @Test
    fun posts_a_sync_notification_when_permission_is_granted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation
                .executeShellCommand(
                    "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
                )
                .close()
        }

        val notifier = SyncNotifier(context)
        notifier.ensureChannel()
        notificationManager.cancelAll()
        notifier.notifySyncFoundChanges()

        assertTrue(
            notificationManager.activeNotifications.any { notification ->
                notification.id == 1001 &&
                    notification.notification.channelId == "sync_updates" &&
                    notification.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) ==
                        context.getString(R.string.app_name)
            }
        )

        notificationManager.cancel(1001)
    }
}
