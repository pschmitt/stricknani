package blue.anika.wolle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.Account.title()) },
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
                    title = stringResource(R.string.account_settings_server_title),
                    icon = Icons.Filled.Dns,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.account_settings_server_url_label))
                        },
                        supportingContent = {
                            Text(
                                serverUrl.ifBlank {
                                    stringResource(R.string.account_settings_not_connected)
                                }
                            )
                        },
                    )
                }
            }
            item {
                SettingsSingleItemCard {
                    SettingsListItem(
                        headlineContent = { Text(stringResource(R.string.settings_sign_out)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_sign_out_description))
                        },
                        leadingContent = {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                    Button(
                        onClick = { showSignOutDialog = true },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("e2e-sign-out-button"),
                    ) {
                        Text(stringResource(R.string.settings_sign_out))
                    }
                }
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.account_settings_sign_out_dialog_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.signOut()
                    },
                    modifier = Modifier.testTag("e2e-sign-out-confirm"),
                ) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
