package blue.anika.wolle.ui.settings

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import blue.anika.wolle.ui.categories.CategoriesScreen

/**
 * Dispatches to the dedicated screen for one [SettingsCategory] (SNA-21). One shared
 * [SettingsViewModel] instance backs every category, matching syncwich's pattern.
 */
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    onBack: () -> Unit,
    onShowLibraries: () -> Unit = {},
    onOpenAdminActivity: () -> Unit = {},
    onOpenAdminAccounts: () -> Unit = {},
    onOpenCategory: (SettingsCategory) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    when (category) {
        SettingsCategory.Account ->
            AccountSettingsScreen(
                onBack = onBack,
                viewModel = viewModel,
                onOpenCategory = onOpenCategory,
            )
        SettingsCategory.Appearance ->
            AppearanceSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.Interface ->
            InterfaceSettingsScreen(
                onBack = onBack,
                viewModel = viewModel,
                onOpenCategory = onOpenCategory,
            )
        SettingsCategory.Navigation ->
            NavigationSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.Categories -> CategoriesScreen(onBack = onBack)
        SettingsCategory.DetailCards -> DetailCardOrderSettingsScreen(onBack = onBack)
        SettingsCategory.Sync -> SyncSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.Backup -> BackupSettingsScreen(onBack = onBack, viewModel = viewModel)
        SettingsCategory.Admin ->
            AdminSettingsScreen(
                onBack = onBack,
                onOpenActivity = onOpenAdminActivity,
                onOpenAccounts = onOpenAdminAccounts,
            )
        SettingsCategory.About ->
            AboutSettingsScreen(
                onBack = onBack,
                onShowLibraries = onShowLibraries,
                viewModel = viewModel,
            )
    }
}
