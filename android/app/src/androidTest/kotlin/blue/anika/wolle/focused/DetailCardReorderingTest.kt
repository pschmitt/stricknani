package blue.anika.wolle.focused

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import blue.anika.wolle.data.settings.DetailCardOrder
import blue.anika.wolle.data.settings.ProjectDetailCard
import blue.anika.wolle.ui.common.DetailCardReorderHint
import blue.anika.wolle.ui.common.ReorderableDetailCard
import blue.anika.wolle.ui.common.rememberDetailCardReorderState
import blue.anika.wolle.ui.theme.StricknaniTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailCardReorderingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun longPressingACardTitleEntersAccessibleReorderMode() {
        composeRule.setContent {
            StricknaniTheme {
                val keys = listOf(ProjectDetailCard.DETAILS, ProjectDetailCard.NOTES)
                val listState = rememberLazyListState()
                val reorderState = rememberDetailCardReorderState()
                LazyColumn(state = listState) {
                    item { DetailCardReorderHint(reorderState, onDone = reorderState::finish) }
                    items(keys, key = { it }) { key ->
                        ReorderableDetailCard(
                            title = if (key == ProjectDetailCard.DETAILS) "Details" else "Notes",
                            cardKey = key,
                            cardIndex = keys.indexOf(key),
                            listState = listState,
                            orderedKeys = keys,
                            itemIndexOffset = 1,
                            reorderState = reorderState,
                            onMove = { _, _ -> },
                        ) {
                            Text("Card content")
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("Details").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                "Details card title Long-press and drag to reorder"
            )
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithText("Drag the card titles into place.").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun reorderHintRemainsReachableWithACompactCardList() {
        composeRule.setContent {
            StricknaniTheme {
                val reorderState = rememberDetailCardReorderState()
                LazyColumn {
                    item { DetailCardReorderHint(reorderState, onDone = reorderState::finish) }
                    item { Text("A card") }
                }
            }
        }

        composeRule.onNodeWithText("Long-press a card title to reorder sections.").assertIsDisplayed()
        composeRule.onNodeWithText("A card").assertIsDisplayed()
    }
}
