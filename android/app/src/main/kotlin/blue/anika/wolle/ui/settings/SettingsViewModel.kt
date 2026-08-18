package blue.anika.wolle.ui.settings

import androidx.lifecycle.ViewModel
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settingsRepository: SettingsRepository) :
    ViewModel() {

    /**
     * Wipes credentials, which flips `SettingsRepository.isConfigured` to false -
     * `MainActivity` observes that and swaps to the Onboarding-rooted nav graph, so no
     * navigation call is needed here. The Room cache (SNA-7) isn't wiped yet - nothing to wipe
     * until that lands.
     */
    fun signOut() {
        settingsRepository.signOut()
    }
}
