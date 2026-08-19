package blue.anika.wolle.focused

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.ui.categories.CategoriesContent
import blue.anika.wolle.ui.categories.CategoriesContentState
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoriesScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun loadingAndEmptyStatesAreRendered() {
        composeRule.setContent {
            StricknaniTheme {
                CategoriesContent(CategoriesContentState.Loading, onRetry = {})
            }
        }
        composeRule.onNodeWithTag("categories-loading").assertIsDisplayed()

        composeRule.setContent {
            StricknaniTheme {
                CategoriesContent(CategoriesContentState.Empty, onRetry = {})
            }
        }
        composeRule.onNodeWithTag("categories-empty").assertIsDisplayed()
        composeRule.onNodeWithText("No categories yet").assertIsDisplayed()
    }

    @Test
    fun offlineAndErrorStatesOfferRetry() {
        var retries = 0
        composeRule.setContent {
            StricknaniTheme {
                CategoriesContent(CategoriesContentState.Offline, onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithTag("categories-offline").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retries == 1)

        composeRule.setContent {
            StricknaniTheme {
                CategoriesContent(CategoriesContentState.Error, onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithTag("categories-error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retries == 2)
    }

    @Test
    fun cachedCategoriesAreDisplayed() {
        composeRule.setContent {
            StricknaniTheme {
                CategoriesContent(
                    CategoriesContentState.Data(
                        listOf(CategoryEntity(id = 1, name = "Sweaters"))
                    ),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("e2e-categories-list").assertIsDisplayed()
        composeRule.onNodeWithText("Sweaters").assertIsDisplayed()
    }
}
