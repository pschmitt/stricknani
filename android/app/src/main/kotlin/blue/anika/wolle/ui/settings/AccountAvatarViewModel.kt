package blue.anika.wolle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.repository.AccountProfile
import blue.anika.wolle.data.repository.AccountProfileRepository
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The signed-in account's avatar for the Settings hub's Account row. */
@HiltViewModel
class AccountAvatarViewModel
@Inject
constructor(
    private val repository: AccountProfileRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    /** Local-only mode: the Account row says "Only on this phone" instead of an email. */
    val isLocalMode: Boolean = settingsRepository.isLocalMode

    val profile: StateFlow<AccountProfile> =
        repository.profile.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AccountProfile(email = null, avatarUrl = null),
        )

    init {
        viewModelScope.launch { repository.refresh() }
    }
}
