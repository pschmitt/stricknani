package blue.anika.wolle.focused

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.ui.settings.BackupPasswordDialog
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SNA-62 regression coverage: the manual-export password dialog's title says "Encrypt backup?
 * (optional)", but the export call site didn't pass `allowBlankToClear = true` - so Confirm stayed
 * disabled for a blank password and there was no way to actually produce the unencrypted export the
 * label promised. Pins down [BackupPasswordDialog]'s two relevant modes directly: with
 * `allowBlankToClear = false` (the restore-from-backup dialog's shape, where a blank password
 * genuinely doesn't make sense) Confirm must stay disabled until something is typed; with
 * `allowBlankToClear = true` (now also the export dialog's shape, matching its "(optional)" label)
 * a blank password must be a legitimate, confirmable choice.
 */
@RunWith(AndroidJUnit4::class)
class BackupPasswordDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun blankPasswordCannotBeConfirmedWhenNotAllowed() {
        var confirmedWith: String? = null
        composeRule.setContent {
            StricknaniTheme {
                BackupPasswordDialog(
                    title = "This backup is password-protected",
                    confirmLabel = "Restore",
                    allowBlankToClear = false,
                    onDismiss = {},
                    onConfirm = { confirmedWith = it },
                )
            }
        }

        composeRule.onNodeWithTag("backup-password-dialog-confirm").assertIsNotEnabled()
        assertEquals(null, confirmedWith)
    }

    @Test
    fun blankPasswordCanBeConfirmedWhenAllowed() {
        // SNA-62: this is exactly the export dialog's shape now - "Encrypt backup? (optional)"
        // must actually allow confirming with no password typed.
        var confirmedWith: String? = null
        composeRule.setContent {
            StricknaniTheme {
                BackupPasswordDialog(
                    title = "Encrypt backup? (optional)",
                    confirmLabel = "Export",
                    allowBlankToClear = true,
                    onDismiss = {},
                    onConfirm = { confirmedWith = it },
                )
            }
        }

        composeRule.onNodeWithTag("backup-password-dialog-confirm").assertIsEnabled()
        composeRule.onNodeWithTag("backup-password-dialog-confirm").performClick()

        assertEquals("", confirmedWith)
    }
}
