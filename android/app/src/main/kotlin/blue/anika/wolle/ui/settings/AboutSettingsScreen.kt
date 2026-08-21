package blue.anika.wolle.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.R

private const val GITHUB_URL = "https://github.com/pschmitt/stricknani"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSettingsScreen(
    onBack: () -> Unit,
    onShowLibraries: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val serverMeta by viewModel.serverMeta.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.developerModeToast.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.About.title()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_nav_back),
                        )
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
                SettingsGroupCard(
                    title = stringResource(R.string.about_settings_version_title),
                    icon = Icons.Filled.Info,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_app_version_label))
                        },
                        supportingContent = {
                            Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        },
                    )
                    SettingsListItem(
                        modifier = Modifier.clickable(onClick = viewModel::onBuildRowTap),
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_build_label))
                        },
                        supportingContent = { Text(BuildConfig.GIT_REVISION) },
                    )
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_server_version_label))
                        },
                        supportingContent = {
                            Text(
                                serverMeta?.let { "${it.version} (${it.buildId})" }
                                    ?: stringResource(
                                        R.string.about_settings_server_version_unavailable
                                    )
                            )
                        },
                    )
                }
            }
            item {
                SettingsSingleItemCard {
                    SettingsListItem(
                        modifier = Modifier.clickable(onClick = onShowLibraries),
                        leadingContent = {
                            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null)
                        },
                        headlineContent = { Text(stringResource(R.string.libraries_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.about_settings_libraries_description))
                        },
                    )
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_license_label))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.about_settings_license_value))
                        },
                    )
                    TextButton(
                        onClick = { uriHandler.openUri(GITHUB_URL) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.about_settings_view_source_button))
                    }
                }
            }
        }
    }
}
