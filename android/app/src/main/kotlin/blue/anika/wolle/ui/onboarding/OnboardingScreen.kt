package blue.anika.wolle.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
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
import blue.anika.wolle.ui.common.DEFAULT_PRIVACY_POLICY_URL
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.YarnLoader
import blue.anika.wolle.ui.common.stitchedBorder

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

private fun OnboardingMode.icon(): ImageVector =
    when (this) {
        OnboardingMode.MANUAL -> Icons.Filled.Key
        OnboardingMode.QR -> Icons.Filled.QrCodeScanner
        OnboardingMode.PASSWORD -> Icons.Filled.Person
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
    val oidcProviders by viewModel.oidcProviders.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(OnboardingMode.PASSWORD) }
    var serverUrl by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val isValidating = uiState is OnboardingUiState.Validating
    val uriHandler = LocalUriHandler.current

    // Look for sign-in providers (Authelia) once the address settles; none are listed today.
    LaunchedEffect(serverUrl) { viewModel.onServerUrlChanged(serverUrl) }

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
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The yarn-ball mascot greets first-time users, as on the website's sign-in page.
        Mascot(size = 168.dp, bob = true)
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        // The form sits on a little stitched patch, like the Continue card on Home.
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 1.dp,
            modifier =
                Modifier.fillMaxWidth()
                    .stitchedBorder(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        cornerRadius = 29.dp,
                    ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboarding-mode",
                ) { shown ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        when (shown) {
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
                            OnboardingMode.QR -> {
                                Icon(
                                    Icons.Filled.QrCodeScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(56.dp).padding(bottom = 8.dp),
                                )
                                Text(
                                    stringResource(R.string.onboarding_qr_instructions),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
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
                    }
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

                Button(
                    onClick = {
                        when (mode) {
                            OnboardingMode.MANUAL -> viewModel.connect(serverUrl, apiToken)
                            OnboardingMode.PASSWORD ->
                                viewModel.signInWithPassword(serverUrl, email, password)
                            OnboardingMode.QR -> showScanner = true
                        }
                    },
                    enabled = !isValidating,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                ) {
                    if (isValidating) {
                        YarnLoader(size = 28.dp, modifier = Modifier.padding(end = 8.dp))
                    } else if (mode == OnboardingMode.QR) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(
                        when (mode) {
                            OnboardingMode.PASSWORD ->
                                stringResource(R.string.onboarding_mode_password)
                            OnboardingMode.QR -> stringResource(R.string.onboarding_qr_scan_button)
                            OnboardingMode.MANUAL ->
                                stringResource(R.string.onboarding_connect_button)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                // Only when the server's meta lists providers. Below the email and password form,
                // as a secondary way in.
                if (mode == OnboardingMode.PASSWORD && oidcProviders.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.onboarding_oidc_or),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    oidcProviders.forEach { provider ->
                        OutlinedButton(
                            onClick = { viewModel.signInWithOidc(serverUrl, provider) },
                            enabled = !isValidating,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(top = 12.dp)
                                    .testTag("onboarding-oidc-${provider.id}"),
                        ) {
                            Icon(
                                PhosphorIcons.LockKey,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                stringResource(R.string.onboarding_oidc_button, provider.name),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }

        // Signing in is the friendly default; the setup code and the token are for people who
        // already have one from the website.
        Text(
            text = stringResource(R.string.onboarding_other_ways),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            OnboardingMode.entries
                .filter { it != mode }
                .forEach { candidate ->
                    AssistChip(
                        onClick = { mode = candidate },
                        enabled = !isValidating,
                        label = { Text(candidate.label()) },
                        leadingIcon = {
                            Icon(candidate.icon(), contentDescription = null, Modifier.size(18.dp))
                        },
                        modifier =
                            Modifier.testTag("onboarding-mode-${candidate.name.lowercase()}"),
                    )
                }
        }
        TextButton(
            onClick = { uriHandler.openUri(DEFAULT_PRIVACY_POLICY_URL) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.onboarding_privacy_link))
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
        // Tell the keyboard this is a secret: no word suggestions or learning, and tools such as
        // adb::rbw-type can see the focused field is a password field.
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
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
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("onboarding-email"),
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.onboarding_password_label)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation =
            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        // Tell the keyboard this is a secret: no word suggestions or learning, and tools such as
        // adb::rbw-type can see the focused field is a password field.
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
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
