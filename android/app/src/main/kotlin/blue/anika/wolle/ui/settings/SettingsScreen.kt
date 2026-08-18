package blue.anika.wolle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Settings hub (SNA-21): a card of category rows, each navigating to its own dedicated screen
 * (`SettingsCategoryScreen`) - matches syncwich's Settings UX rather than SNA-18's original single
 * flat scrolling list. The SNA-21 redesign dropped the page header along with the old flat list -
 * every other top-level destination (Home's `TopAppBar`, Projects'/Yarns' search field) has some
 * visual anchor confirming which screen you're on; this one didn't (user-reported, 2026-08-18).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onCategoryClick: (SettingsCategory) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("settings-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsSingleItemCard {
                    SettingsCategory.entries.forEach { category ->
                        SettingsCategoryRow(category, onCategoryClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(category: SettingsCategory, onClick: (SettingsCategory) -> Unit) {
    SettingsListItem(
        modifier =
            Modifier.clickable(role = Role.Button) { onClick(category) }
                .semantics {
                    contentDescription = "${category.title}: ${category.subtitle}"
                    role = Role.Button
                },
        leadingContent = { Icon(category.icon, contentDescription = null) },
        headlineContent = { Text(category.title) },
        supportingContent = { Text(category.subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
    )
}
