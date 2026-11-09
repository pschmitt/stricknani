package blue.anika.wolle.ui.palette

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.AdminRepository
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The searchable things from Room (offline, like the lists' own search), newest first. */
data class PaletteData(
    val projects: List<PaletteEntry> = emptyList(),
    val yarns: List<PaletteEntry> = emptyList(),
    val categories: List<PaletteEntry> = emptyList(),
) {
    fun all(): List<PaletteEntry> = projects + yarns + categories
}

@HiltViewModel
class PaletteViewModel
@Inject
constructor(
    projectRepository: ProjectRepository,
    yarnRepository: YarnRepository,
    categoryRepository: CategoryRepository,
    adminRepository: AdminRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val preferences: AppPreferencesRepository,
) : ViewModel() {
    val data: StateFlow<PaletteData> =
        combine(
                projectRepository.observeAll(),
                yarnRepository.observeAll(),
                categoryRepository.observeAll(),
            ) { projects, yarns, categories ->
                PaletteData(
                    PaletteEntries.projects(projects, mediaUrlResolver::resolve),
                    PaletteEntries.yarns(yarns, mediaUrlResolver::resolve),
                    PaletteEntries.categories(categories),
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                PaletteData(),
            )

    val isAdmin: StateFlow<Boolean> =
        adminRepository.isAdmin
            .map { it == true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    val themeMode: StateFlow<ThemeMode> =
        preferences.themeMode.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ThemeMode.SYSTEM,
        )

    fun setDarkMode(dark: Boolean) {
        viewModelScope.launch {
            preferences.setThemeMode(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
