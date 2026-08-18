package blue.anika.wolle.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Appearance.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroupCard(title = "Theme", icon = Icons.Filled.Palette) {
                    ThemeMode.entries.forEach { mode ->
                        SettingsListItem(
                            modifier =
                                Modifier.fillMaxWidth().clickable { viewModel.setThemeMode(mode) },
                            headlineContent = { Text(mode.label()) },
                            supportingContent = {
                                if (mode == ThemeMode.SYSTEM) {
                                    Text("Follow the device appearance setting")
                                }
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun ThemeMode.label(): String =
    when (this) {
        ThemeMode.SYSTEM -> "Follow system"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }
