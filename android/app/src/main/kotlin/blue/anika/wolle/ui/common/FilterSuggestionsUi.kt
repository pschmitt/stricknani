package blue.anika.wolle.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.admin.AdminAccessViewModel

/** The translated labels of the fixed filters, the same strings as the filter chips. */
@Composable
fun rememberFixedFilterLabels(): FixedFilterLabels =
    FixedFilterLabels(
        inProgress = stringResource(R.string.project_status_in_progress),
        done = stringResource(R.string.project_status_done),
        mine = stringResource(R.string.owner_filter_mine),
        shared = stringResource(R.string.owner_filter_shared),
        favorites = stringResource(R.string.yarns_list_favorites_only),
        trash = stringResource(R.string.list_filter_trash),
    )

/** Whether the signed-in user is an administrator (owner suggestions are theirs alone). */
@Composable
fun rememberIsAdmin(viewModel: AdminAccessViewModel = hiltViewModel()): Boolean {
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    return isAdmin
}

private fun SuggestionGroup.labelRes(): Int =
    when (this) {
        SuggestionGroup.Tags -> R.string.filter_suggestions_group_tags
        SuggestionGroup.Categories -> R.string.filter_suggestions_group_categories
        SuggestionGroup.Status -> R.string.filter_suggestions_group_status
        SuggestionGroup.Owners -> R.string.filter_suggestions_group_owners
        SuggestionGroup.Other -> R.string.filter_suggestions_group_other
    }

/**
 * The search field of a library with [suggestions] listed below it, grouped as on the website.
 * Picking one calls [onSuggestion] (the screen applies the filter and clears the typed text). With
 * a hardware keyboard the arrow keys move through the list, Enter picks and Escape (or Back) hides
 * it until the text changes. The list is a polite pane for TalkBack, with a heading per group.
 */
@Composable
fun FilterSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    suggestions: List<FilterSuggestion>,
    onSuggestion: (FilterSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    var activeIndex by remember(suggestions) { mutableIntStateOf(-1) }
    val visible = focused && suggestions.isNotEmpty() && dismissedFor != value
    BackHandler(enabled = visible) { dismissedFor = value }

    Column(modifier) {
        SearchField(
            value = value,
            onValueChange = {
                dismissedFor = null
                onValueChange(it)
            },
            placeholder = placeholder,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(16.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (!visible || event.type != KeyEventType.KeyDown)
                            return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                activeIndex = (activeIndex + 1) % suggestions.size
                                true
                            }
                            Key.DirectionUp -> {
                                activeIndex =
                                    if (activeIndex <= 0) suggestions.size - 1 else activeIndex - 1
                                true
                            }
                            Key.Enter,
                            Key.NumPadEnter ->
                                suggestions.getOrNull(activeIndex)?.let {
                                    onSuggestion(it)
                                    true
                                } ?: false
                            Key.Escape -> {
                                dismissedFor = value
                                true
                            }
                            else -> false
                        }
                    },
        )
        if (visible) {
            val title =
                pluralStringResource(
                    R.plurals.filter_suggestions_count,
                    suggestions.size,
                    suggestions.size,
                )
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 3.dp,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .testTag("filter-suggestions")
                        .semantics { paneTitle = title },
            ) {
                Column(
                    Modifier.heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    SuggestionGroup.entries.forEach { group ->
                        val rows = suggestions.withIndex().filter { it.value.group == group }
                        if (rows.isEmpty()) return@forEach
                        Text(
                            stringResource(group.labelRes()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp).semantics {
                                    heading()
                                },
                        )
                        rows.forEach { (index, suggestion) ->
                            val applyLabel = stringResource(R.string.filter_suggestion_apply)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .background(
                                            if (index == activeIndex)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else Color.Transparent
                                        )
                                        .semantics { selected = index == activeIndex }
                                        .clickable(
                                            onClickLabel = applyLabel,
                                            role = Role.Button,
                                            onClick = { onSuggestion(suggestion) },
                                        )
                                        .padding(horizontal = 16.dp)
                                        .testTag("filter-suggestion"),
                            ) {
                                val owner = suggestion.owner
                                if (owner != null) {
                                    UserLabel(
                                        ownerRef(owner),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                } else {
                                    Text(
                                        suggestion.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
