package blue.anika.wolle.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.AppLanguage
import blue.anika.wolle.ui.common.PhosphorIcons

/** Language: the app language (follow the system, English or German), moved out of Appearance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Language.title()) },
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
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("language-settings"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.appearance_settings_language_title),
                    icon = PhosphorIcons.Translate,
                ) {
                    AppLanguage.entries.forEach { language ->
                        SettingsListItem(
                            modifier =
                                Modifier.fillMaxWidth().clickable {
                                    viewModel.setAppLanguage(language)
                                    activity?.recreate()
                                },
                            headlineContent = { Text(language.label()) },
                            trailingContent = {
                                RadioButton(
                                    selected = appLanguage == language,
                                    onClick = {
                                        viewModel.setAppLanguage(language)
                                        activity?.recreate()
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLanguage.label(): String =
    when (this) {
        AppLanguage.SYSTEM -> stringResource(R.string.appearance_settings_theme_system_label)
        AppLanguage.ENGLISH -> "English"
        AppLanguage.GERMAN -> "Deutsch"
    }
