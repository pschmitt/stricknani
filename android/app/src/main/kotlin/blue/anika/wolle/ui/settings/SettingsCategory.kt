package blue.anika.wolle.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The groups shown by the Settings hub screen (SNA-21) - matches syncwich's
 * `SettingsCategory`/`SettingsCategoryScreen` pattern: a card-based hub navigating to a dedicated
 * full screen per category, instead of one long flat list (SNA-18's original shape).
 */
enum class SettingsCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    Account("Account", "Server connection and sign-out", Icons.Filled.Dns),
    Appearance("Appearance", "Theme", Icons.Filled.Palette),
    Navigation("Navigation", "Choose destinations and their order", Icons.Filled.ViewCarousel),
    Sync("Sync", "Background refresh status", Icons.Filled.Sync),
    Backup("Backup", "Export, restore, and scheduled backups", Icons.Filled.Backup),
    About("About", "Application and server information", Icons.Filled.Info),
}
