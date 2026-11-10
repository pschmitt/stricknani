package blue.anika.wolle.ui.common

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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

/** The administrator's Mine choice for the project and yarn lists, as the list screen uses it. */
@Composable
fun rememberEveryoneFilter(
    viewModel: EveryoneFilterViewModel = hiltViewModel()
): Pair<EveryoneFilterState, (everyone: Boolean) -> Unit> {
    val state by viewModel.state.collectAsStateWithLifecycle()
    return state to viewModel::select
}

/**
 * The administrator's "Mine" filter for the project and yarn lists, one chip in the filter row next
 * to the other filters (the website offers it in the filter panel). Selected means only the
 * administrator's own items; Everyone is the default and needs no chip. Not shown for anybody else.
 */
@Composable
fun MineFilterChip(
    state: EveryoneFilterState,
    onSelect: (everyone: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.available) return
    FilterChip(
        selected = !state.enabled,
        onClick = { onSelect(!state.enabled) },
        label = { Text(stringResource(R.string.owner_filter_mine)) },
        modifier = modifier.testTag("everyone-filter-mine"),
    )
}
