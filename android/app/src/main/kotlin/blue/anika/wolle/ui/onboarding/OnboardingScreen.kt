package blue.anika.wolle.ui.onboarding

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R

private enum class OnboardingMode {
    MANUAL,
    QR,
    PASSWORD,
}

@Composable
private fun OnboardingMode.label(): String =
    when (this) {
        OnboardingMode.MANUAL -> stringResource(R.string.onboarding_mode_manual)
        OnboardingMode.QR -> stringResource(R.string.onboarding_mode_qr)
        OnboardingMode.PASSWORD -> stringResource(R.string.onboarding_mode_password)
    }

/**
 * Server URL + personal access token entry, plus two SNA-13 shortcuts that both end up at the same
 * [OnboardingViewModel.connect]/[OnboardingViewModel.persistAndSucceed] destination: scanning the
 * web Settings page's setup QR, or signing in with an email/password (which mints a PAT server-side
 * instead of requiring a trip to the web UI first). Validated against the real server
 * ([blue.anika.wolle.data.onboarding.OnboardingValidator]) before anything is saved - see
 * [OnboardingViewModel]. Success flips
 * [blue.anika.wolle.data.settings.SettingsRepository.isConfigured], which `MainActivity` observes
 * to swap to the Home-rooted nav graph; this screen doesn't navigate itself.
 *
 * @param pendingScannedText a `stricknani://setup?p=...` URI delivered via the manifest's own
 *   intent-filter (SNA-61) instead of the in-app scanner - e.g. a generic camera app's own QR
 *   auto-detection. Fed through [OnboardingViewModel.connectFromScannedText] exactly like a
 *   [QrScannerDialog] result once, then cleared via [onScannedTextConsumed]. `null` means nothing
 *   pending.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    pendingScannedText: String? = null,
    onScannedTextConsumed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(OnboardingMode.MANUAL) }
    var serverUrl by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val isValidating = uiState is OnboardingUiState.Validating

    // SNA-61: a stricknani://setup URI delivered by the OS (not the in-app scanner) goes through
    // the exact same connectFromScannedText path a real scan result does - switching to QR mode
    // first just keeps the visible UI consistent with what's actually happening.
    LaunchedEffect(pendingScannedText) {
        pendingScannedText?.let { text ->
            mode = OnboardingMode.QR
            viewModel.connectFromScannedText(text)
            onScannedTextConsumed()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            OnboardingMode.entries.forEach { candidate ->
                FilterChip(
                    selected = mode == candidate,
                    onClick = { mode = candidate },
                    enabled = !isValidating,
                    label = { Text(candidate.label()) },
                )
            }
        }
        Spacer(Modifier.padding(top = 16.dp))

        when (mode) {
            OnboardingMode.MANUAL ->
                ManualOnboardingFields(
                    serverUrl = serverUrl,
                    onServerUrlChange = { serverUrl = it },
                    apiToken = apiToken,
                    onApiTokenChange = { apiToken = it },
                    tokenVisible = tokenVisible,
                    onTokenVisibleChange = { tokenVisible = it },
                    enabled = !isValidating,
                )
            OnboardingMode.QR ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.onboarding_qr_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { showScanner = true },
                        enabled = !isValidating,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(R.string.onboarding_qr_scan_button))
                    }
                }
            OnboardingMode.PASSWORD ->
                PasswordOnboardingFields(
                    serverUrl = serverUrl,
                    onServerUrlChange = { serverUrl = it },
                    email = email,
                    onEmailChange = { email = it },
                    password = password,
                    onPasswordChange = { password = it },
                    passwordVisible = passwordVisible,
                    onPasswordVisibleChange = { passwordVisible = it },
                    enabled = !isValidating,
                )
        }

        if (uiState is OnboardingUiState.Error) {
            Text(
                text = (uiState as OnboardingUiState.Error).message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        if (mode != OnboardingMode.QR) {
            Button(
                onClick = {
                    when (mode) {
                        OnboardingMode.MANUAL -> viewModel.connect(serverUrl, apiToken)
                        OnboardingMode.PASSWORD ->
                            viewModel.signInWithPassword(serverUrl, email, password)
                        OnboardingMode.QR -> Unit
                    }
                },
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
                Text(
                    if (mode == OnboardingMode.PASSWORD)
                        stringResource(R.string.onboarding_mode_password)
                    else stringResource(R.string.onboarding_connect_button)
                )
            }
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onResult = { text ->
                showScanner = false
                viewModel.connectFromScannedText(text)
            },
            onDismiss = { showScanner = false },
        )
    }
}

@Composable
private fun ManualOnboardingFields(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    tokenVisible: Boolean,
    onTokenVisibleChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(R.string.onboarding_server_url_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_server_url_placeholder)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("e2e-onboarding-server-url"),
    )

    OutlinedTextField(
        value = apiToken,
        onValueChange = onApiTokenChange,
        label = { Text(stringResource(R.string.onboarding_api_token_label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation =
            if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { onTokenVisibleChange(!tokenVisible) }) {
                Icon(
                    imageVector =
                        if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription =
                        stringResource(
                            if (tokenVisible) R.string.onboarding_hide_token
                            else R.string.onboarding_show_token
                        ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("e2e-onboarding-api-token"),
    )

    Text(
        text = stringResource(R.string.onboarding_api_token_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PasswordOnboardingFields(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibleChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(R.string.onboarding_server_url_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_server_url_placeholder)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("e2e-onboarding-server-url"),
    )

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text(stringResource(R.string.onboarding_email_label)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.onboarding_password_label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { onPasswordVisibleChange(!passwordVisible) }) {
                Icon(
                    imageVector =
                        if (passwordVisible) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                    contentDescription =
                        stringResource(
                            if (passwordVisible) R.string.onboarding_hide_password
                            else R.string.onboarding_show_password
                        ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}
