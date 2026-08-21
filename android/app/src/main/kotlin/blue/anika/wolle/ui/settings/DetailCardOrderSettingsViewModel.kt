package blue.anika.wolle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.settings.DetailCardDomain
import blue.anika.wolle.data.settings.DetailCardOrder
import blue.anika.wolle.data.settings.DetailCardOrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DetailCardOrderSettingsViewModel
@Inject
constructor(private val repository: DetailCardOrderRepository) : ViewModel() {

    val projectOrder: StateFlow<List<String>> =
        repository.projectOrder.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            DetailCardOrder.defaults(DetailCardDomain.PROJECT),
        )
    val yarnOrder: StateFlow<List<String>> =
        repository.yarnOrder.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            DetailCardOrder.defaults(DetailCardDomain.YARN),
        )

    fun move(domain: DetailCardDomain, fromIndex: Int, toIndex: Int) {
        val current =
            if (domain == DetailCardDomain.PROJECT) projectOrder.value else yarnOrder.value
        val moved = DetailCardOrder.move(current, fromIndex, toIndex)
        if (moved != current) viewModelScope.launch { repository.setOrder(domain, moved) }
    }

    fun reset(domain: DetailCardDomain) {
        viewModelScope.launch { repository.reset(domain) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
