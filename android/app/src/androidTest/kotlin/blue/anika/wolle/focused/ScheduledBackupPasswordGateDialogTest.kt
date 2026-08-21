package blue.anika.wolle.focused

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.ui.settings.ScheduledBackupPasswordGateDialog
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SNA-59 regression coverage: enabling scheduled backup used to jump straight to the SAF folder
 * picker with zero password prompt, producing an unencrypted, token-bearing export on every
 * scheduled run with no warning at all. [ScheduledBackupPasswordGateDialog] is the fix - an
 * un-skippable choice shown before scheduled backup can be enabled without a password already
 * set. These tests pin down that its two explicit actions ("set a password" vs "continue without
 * one") behave correctly and that there is no way to reach either callback with a stale/blank
 * password silently accepted as if it were a real one.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledBackupPasswordGateDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun warningAndBothChoicesAreVisible() {
        composeRule.setContent {
            StricknaniTheme {
                ScheduledBackupPasswordGateDialog(
                    onDismiss = {},
                    onSetPassword = {},
                    onContinueWithoutPassword = {},
                )
            }
        }

        composeRule.onNodeWithText("Protect scheduled backups?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Scheduled backups run automatically and include your live Stricknani sign-in " +
                    "token. Without a password, every scheduled backup is a plain, readable file " +
                    "- and the destination folder you pick is often synced to the cloud. Set a " +
                    "password to encrypt scheduled backups, or continue without one."
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Set password and enable").assertIsDisplayed()
        composeRule.onNodeWithText("Enable without a password").assertIsDisplayed()
    }

    @Test
    fun setPasswordButtonStaysDisabledUntilAPasswordIsTyped() {
        var setPassword: String? = null
        composeRule.setContent {
            StricknaniTheme {
                ScheduledBackupPasswordGateDialog(
                    onDismiss = {},
                    onSetPassword = { setPassword = it },
                    onContinueWithoutPassword = {},
                )
            }
        }

        composeRule.onNodeWithTag("scheduled-backup-gate-set-password").assertIsNotEnabled()

        composeRule.onNodeWithTag("scheduled-backup-gate-password").performTextInput("hunter2")
        composeRule.onNodeWithTag("scheduled-backup-gate-set-password").assertIsEnabled()
        composeRule.onNodeWithTag("scheduled-backup-gate-set-password").performClick()

        assertEquals("hunter2", setPassword)
    }

    @Test
    fun continuingWithoutAPasswordIsItsOwnExplicitActionAndDoesNotSetOne() {
        var setPasswordCalled = false
        var continuedWithoutPassword = false
        composeRule.setContent {
            StricknaniTheme {
                ScheduledBackupPasswordGateDialog(
                    onDismiss = {},
                    onSetPassword = { setPasswordCalled = true },
                    onContinueWithoutPassword = { continuedWithoutPassword = true },
                )
            }
        }

        composeRule.onNodeWithTag("scheduled-backup-gate-skip").performClick()

        assertTrue(continuedWithoutPassword)
        assertFalse(setPasswordCalled)
    }
}
