package blue.anika.wolle.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.repository.AdminRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether to show the Admin entry in Settings. The probe itself is cached in [AdminRepository]. */
@HiltViewModel
class AdminAccessViewModel @Inject constructor(private val repository: AdminRepository) :
    ViewModel() {
    val isAdmin: StateFlow<Boolean> =
        repository.isAdmin
            .map { it == true }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                repository.isAdmin.value == true,
            )

    init {
        viewModelScope.launch { repository.probeAdmin() }
    }
}
