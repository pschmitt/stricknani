package blue.anika.wolle.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R

/**
 * Server URL + personal access token entry. Validated against the real server
 * ([blue.anika.wolle.data.onboarding.OnboardingValidator]) before anything is saved - see
 * [OnboardingViewModel]. Success flips
 * [blue.anika.wolle.data.settings.SettingsRepository.isConfigured], which `MainActivity` observes
 * to swap to the Home-rooted nav graph; this screen doesn't navigate itself.
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var serverUrl by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    val isValidating = uiState is OnboardingUiState.Validating

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text(stringResource(R.string.onboarding_server_url_label)) },
            placeholder = { Text(stringResource(R.string.onboarding_server_url_placeholder)) },
            singleLine = true,
            enabled = !isValidating,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = apiToken,
            onValueChange = { apiToken = it },
            label = { Text(stringResource(R.string.onboarding_api_token_label)) },
            singleLine = true,
            enabled = !isValidating,
            visualTransformation =
                if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        imageVector =
                            if (tokenVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                        contentDescription =
                            stringResource(
                                if (tokenVisible) R.string.onboarding_hide_token
                                else R.string.onboarding_show_token
                            ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Text(
            text = stringResource(R.string.onboarding_api_token_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (uiState is OnboardingUiState.Error) {
            Text(
                text = (uiState as OnboardingUiState.Error).message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Button(
            onClick = { viewModel.connect(serverUrl, apiToken) },
            enabled = !isValidating,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            if (isValidating) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).padding(end = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            }
            Text(stringResource(R.string.onboarding_connect_button))
        }
    }
}
