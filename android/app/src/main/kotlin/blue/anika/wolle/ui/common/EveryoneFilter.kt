package blue.anika.wolle.ui.common

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.EveryoneFilterState
import blue.anika.wolle.data.repository.EveryoneScope
import blue.anika.wolle.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class EveryoneFilterViewModel
@Inject
constructor(private val everyoneScope: EveryoneScope, private val syncScheduler: SyncScheduler) :
    ViewModel() {
    val state: StateFlow<EveryoneFilterState> =
        everyoneScope.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            EveryoneFilterState(),
        )

    /** Mine (false) or Everyone (true); switching purges or fetches other users' items. */
    fun select(everyone: Boolean) {
        if (everyone == state.value.enabled) return
        viewModelScope.launch {
            everyoneScope.setEnabled(everyone)
            syncScheduler.syncNow()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * The administrator's "Mine | Everyone" choice for the project and yarn lists. Not shown for
 * anybody else; Everyone is the default for administrators.
 */
@Composable
fun EveryoneFilterChips(
    modifier: Modifier = Modifier,
    viewModel: EveryoneFilterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EveryoneFilterChips(state = state, onSelect = viewModel::select, modifier = modifier)
}

@Composable
fun EveryoneFilterChips(
    state: EveryoneFilterState,
    onSelect: (everyone: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.available) return
    val label = stringResource(R.string.owner_filter_label)
    // A segmented control on its own line, like the website's ScopeFilter: it chooses whose
    // items the list shows and must not read as one more category chip.
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.semantics { contentDescription = label }.testTag("everyone-filter")
    ) {
        SegmentedButton(
            selected = !state.enabled,
            onClick = { onSelect(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.testTag("everyone-filter-mine"),
        ) {
            Text(stringResource(R.string.owner_filter_mine))
        }
        SegmentedButton(
            selected = state.enabled,
            onClick = { onSelect(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.testTag("everyone-filter-everyone"),
        ) {
            Text(stringResource(R.string.owner_filter_everyone))
        }
    }
}
