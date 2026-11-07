package blue.anika.wolle.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons

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
        PhosphorIcons.HardDrives,
    ),
    Language(
        R.string.settings_category_language_title,
        R.string.settings_category_language_subtitle,
        PhosphorIcons.Translate,
    ),
    /** Opened from User interface, not from More itself. */
    Appearance(
        R.string.settings_category_appearance_title,
        R.string.settings_category_appearance_subtitle,
        PhosphorIcons.Palette,
    ),
    /** User interface: groups appearance, the bottom bar, project timers and detail cards. */
    Interface(
        R.string.settings_category_interface_title,
        R.string.settings_category_interface_subtitle,
        PhosphorIcons.Layout,
    ),
    Navigation(
        R.string.settings_category_navigation_title,
        R.string.settings_category_navigation_subtitle,
        PhosphorIcons.Compass,
    ),
    Categories(
        R.string.settings_category_categories_title,
        R.string.settings_category_categories_subtitle,
        PhosphorIcons.Tag,
    ),
    DetailCards(
        R.string.settings_category_detail_cards_title,
        R.string.settings_category_detail_cards_subtitle,
        PhosphorIcons.SquaresFour,
    ),
    Sync(
        R.string.settings_category_sync_title,
        R.string.settings_category_sync_subtitle,
        PhosphorIcons.CloudCheck,
    ),
    Backup(
        R.string.settings_category_backup_title,
        R.string.settings_category_backup_subtitle,
        PhosphorIcons.FloppyDisk,
    ),
    /** Only shown to administrators (see `AdminAccessViewModel`). */
    Admin(
        R.string.settings_category_admin_title,
        R.string.settings_category_admin_subtitle,
        PhosphorIcons.ShieldCheck,
    ),
    About(
        R.string.settings_category_about_title,
        R.string.settings_category_about_subtitle,
        PhosphorIcons.Info,
    ),
}

@Composable fun SettingsCategory.title(): String = stringResource(titleRes)

@Composable fun SettingsCategory.subtitle(): String = stringResource(subtitleRes)
