package blue.anika.wolle.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R

/**
 * The groups shown by the Settings hub screen (SNA-21) - matches syncwich's
 * `SettingsCategory`/`SettingsCategoryScreen` pattern: a card-based hub navigating to a dedicated
 * full screen per category, instead of one long flat list (SNA-18's original shape).
 */
enum class SettingsCategory(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
) {
    Account(
        R.string.settings_category_account_title,
        R.string.settings_category_account_subtitle,
        Icons.Filled.Dns,
    ),
    Appearance(
        R.string.settings_category_appearance_title,
        R.string.settings_category_appearance_subtitle,
        Icons.Filled.Palette,
    ),
    Navigation(
        R.string.settings_category_navigation_title,
        R.string.settings_category_navigation_subtitle,
        Icons.Filled.ViewCarousel,
    ),
    Sync(
        R.string.settings_category_sync_title,
        R.string.settings_category_sync_subtitle,
        Icons.Filled.Sync,
    ),
    Backup(
        R.string.settings_category_backup_title,
        R.string.settings_category_backup_subtitle,
        Icons.Filled.Backup,
    ),
    About(
        R.string.settings_category_about_title,
        R.string.settings_category_about_subtitle,
        Icons.Filled.Info,
    ),
}

@Composable fun SettingsCategory.title(): String = stringResource(titleRes)

@Composable fun SettingsCategory.subtitle(): String = stringResource(subtitleRes)
