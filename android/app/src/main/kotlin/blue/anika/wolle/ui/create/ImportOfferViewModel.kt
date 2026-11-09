package blue.anika.wolle.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.offersProjectImport
import blue.anika.wolle.data.api.offersYarnImport
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Whether imports are offered, for the Create screen, the Create card on Home and the one in More:
 * their texts mention importing only when it works (server mode with imports available).
 */
@HiltViewModel
class ImportOfferViewModel
@Inject
constructor(capabilities: ApiCapabilities, private val settings: SettingsRepository) : ViewModel() {
    val offers: StateFlow<ImportOffers> =
        capabilities.state
            .map {
                ImportOffers(
                    project = it.offersProjectImport(settings.isLocalMode),
                    yarn = it.offersYarnImport(settings.isLocalMode),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                ImportOffers(
                    project = capabilities.current.offersProjectImport(settings.isLocalMode),
                    yarn = capabilities.current.offersYarnImport(settings.isLocalMode),
                ),
            )
}
