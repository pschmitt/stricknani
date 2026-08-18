package blue.anika.wolle.ui.settings

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Dispatches to the dedicated screen for one [SettingsCategory] (SNA-21). One shared
 * [SettingsViewModel] instance backs every category, matching syncwich's pattern.
 */
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    when (category) {
        SettingsCategory.Account -> AccountSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.Appearance ->
            AppearanceSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.Navigation ->
            NavigationSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.Sync -> SyncSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.About -> AboutSettingsScreen(onBack = onBack, viewModel = viewModel)
    }
}
