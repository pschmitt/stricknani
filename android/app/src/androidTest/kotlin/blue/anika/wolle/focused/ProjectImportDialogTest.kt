package blue.anika.wolle.focused

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.ui.projects.ProjectImportDialog
import blue.anika.wolle.ui.projects.ProjectImportState
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectImportDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun entryPointAcceptsUrlAndExposesCancellation() {
        var startedUrl: String? = null
        var cancelled = false
        composeRule.setContent {
            StricknaniTheme {
                ProjectImportDialog(
                    visible = true,
                    state = ProjectImportState.Idle,
                    onStart = { startedUrl = it },
                    onConfirm = {},
                    onCancel = { cancelled = true },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("project-import-url").performTextInput("https://example.com/pattern")
        composeRule.onNodeWithText("Import").performClick()
        assertEquals("https://example.com/pattern", startedUrl)

        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun previewRequiresConfirmationAndReportsImageLimitation() {
        var confirmed = false
        val preview =
            ImportedProjectPreview(
                sourceUrl = "https://example.com/pattern",
                request = ProjectWriteRequest(name = "Cardigan"),
                imageUrls = listOf("https://example.com/cardigan.jpg"),
                aiFallback = false,
            )
        composeRule.setContent {
            StricknaniTheme {
                ProjectImportDialog(
                    visible = true,
                    state = ProjectImportState.AwaitingConfirmation(preview),
                    onStart = {},
                    onConfirm = { confirmed = true },
                    onCancel = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Review imported project").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "The current API returns image URLs but does not attach them to a new offline project yet. The text and steps will be saved; you can add images later."
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("Add project").performClick()
        assertTrue(confirmed)
    }
}
