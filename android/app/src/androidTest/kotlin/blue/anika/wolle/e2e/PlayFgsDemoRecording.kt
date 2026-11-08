package blue.anika.wolle.e2e

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText as hasTextMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: the walk-through recorded for Google Play's foreground-service declaration
 * (`FOREGROUND_SERVICE_DATA_SYNC`, "Backing up, restoring"; see
 * `android/docs/play-store-release.md`). Runs only in the `fgs-demo` E2E lane, which records the
 * emulator's screen around it (`android/scripts/run-e2e-tests.sh`).
 *
 * On Android 8-13 a data download and a restore run as a `dataSync` foreground service, with their
 * progress notification. The walk-through starts both from Settings -> Backups through the system
 * file picker (a fresh emulator's Downloads folder is empty) and opens the notification shade while
 * they run and when they are done.
 */
@RunWith(AndroidJUnit4::class)
class PlayFgsDemoRecording : StricknaniE2eTest() {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun downloadAndRestoreWithTheirNotifications() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("only for the fgs-demo recording", arguments.getString("fgs_demo") == "true")
        connectToFixture()
        pause(2_000)

        composeRule.onNodeWithText("More").performClick()
        waitForText("Settings")
        pause(1_500)
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasTextMatcher("Backups"))
        pause(1_000)
        composeRule.onNodeWithText("Backups").performClick()
        waitForText("Your data")
        pause(2_000)

        // Download: the system picker asks where to save the archive.
        composeRule
            .onNodeWithTag("data-export-button")
            .performSemanticsAction(SemanticsActions.OnClick)
        clickSystemButton("SAVE", "Save")
        showNotifications("Your data is saved")

        // Restore the file just saved.
        scrollBackupsTo("Choose file")
        pause(1_000)
        // The "saved" snackbar can sit on top of the button: click through its semantics action.
        composeRule
            .onNodeWithTag("data-restore-button")
            .performSemanticsAction(SemanticsActions.OnClick)
        val file = device.wait(Until.findObject(By.textStartsWith("stricknani-export")), 30_000)
        checkNotNull(file) { "the saved archive is not listed in the file picker" }
        pause(1_500)
        file.click()
        // The compose rule drives the app's frames: wait through it, or the picked file never
        // recomposes the screen and the confirmation is never drawn.
        try {
            composeRule.waitUntil(30_000) {
                composeRule
                    .onAllNodesWithText("Restore this file?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            // One line: CI's failure summary keeps only the first line of a message.
            val log = pickerLog().lines().filter { it.isNotBlank() }.joinToString(" ⏎ ")
            throw AssertionError("no restore confirmation. Log: [$log]", failure)
        }
        pause(1_500)
        composeRule.onNodeWithText("Restore").performClick()
        showNotifications("Restore finished")

        composeRule.onNodeWithText("Projects").performClick()
        pause(4_000)
    }

    /** The app's picker result and the activity switches around it; no file names or URIs. */
    private fun pickerLog(): String {
        val command =
            "logcat -d -t 400 -s StricknaniBackup:V ActivityTaskManager:I AndroidRuntime:E " +
                "DocumentsUI:W"
        val output =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use {
            it.readText().takeLast(3_000)
        }
    }

    /** Clicks the system picker's confirm button, whatever its capitalisation. */
    private fun clickSystemButton(vararg labels: String) {
        val pattern = Pattern.compile(labels.joinToString("|") { Pattern.quote(it) })
        val button = device.wait(Until.findObject(By.text(pattern).clickable(true)), 30_000)
        checkNotNull(button) { "no ${labels.first()} button in the system picker" }
        pause(1_500)
        button.click()
    }

    /** Opens the shade, keeps it open until [doneTitle] shows (and a moment longer), closes it. */
    private fun showNotifications(doneTitle: String) {
        pause(500)
        device.openNotification()
        device.wait(Until.hasObject(By.text(doneTitle)), 60_000)
        pause(5_000)
        device.pressBack()
        pause(1_500)
    }

    private fun scrollBackupsTo(text: String) {
        composeRule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasTextMatcher(text))
    }

    /**
     * Waits in real time and lets the app draw meanwhile: the compose rule's clock only moves when
     * the test advances it, so a plain sleep freezes the screen (and the recording).
     */
    private fun pause(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, FRAME_STEP_MILLIS)
            composeRule.mainClock.advanceTimeBy(step)
            SystemClock.sleep(step)
            left -= step
        }
    }

    private companion object {
        const val FRAME_STEP_MILLIS = 100L
    }
}
