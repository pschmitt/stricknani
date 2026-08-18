package blue.anika.wolle.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.settings.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs `StricknaniNavHost`'s bottom nav bar (SNA-16) - a thin wrapper so the bar can read the
 * user's configured, sanitized visible-destination order with `hiltViewModel()` like every other
 * screen-level ViewModel, rather than threading `AppPreferencesRepository` into the nav host.
 */
@HiltViewModel
class NavBarViewModel @Inject constructor(appPreferencesRepository: AppPreferencesRepository) :
    ViewModel() {

    val visibleDestinations: StateFlow<List<TopLevelDestination>> =
        appPreferencesRepository.navbarItems
            .map { raw ->
                NavbarCustomization.visibleDestinations(NavbarCustomization.sanitize(raw))
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                TopLevelDestination.entries.toList(),
            )
}
