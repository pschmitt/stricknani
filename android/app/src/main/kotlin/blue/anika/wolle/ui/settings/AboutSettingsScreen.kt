package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.BuildConfig

private const val GITHUB_URL = "https://github.com/pschmitt/stricknani"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val serverMeta by viewModel.serverMeta.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.About.title) },
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
                SettingsGroupCard(title = "Version", icon = Icons.Filled.Info) {
                    SettingsListItem(
                        headlineContent = { Text("App version") },
                        supportingContent = {
                            Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        },
                    )
                    SettingsListItem(
                        headlineContent = { Text("Build") },
                        supportingContent = { Text(BuildConfig.GIT_REVISION) },
                    )
                    SettingsListItem(
                        headlineContent = { Text("Server version") },
                        supportingContent = {
                            Text(
                                serverMeta?.let { "${it.version} (${it.buildId})" } ?: "Unavailable"
                            )
                        },
                    )
                }
            }
            item {
                SettingsSingleItemCard {
                    SettingsListItem(
                        headlineContent = { Text("License") },
                        supportingContent = { Text("GPL-3.0") },
                    )
                    TextButton(
                        onClick = { uriHandler.openUri(GITHUB_URL) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Text("View source on GitHub")
                    }
                }
            }
        }
    }
}
