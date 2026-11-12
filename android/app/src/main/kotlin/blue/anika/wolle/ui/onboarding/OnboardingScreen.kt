package blue.anika.wolle.ui.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.ui.common.DEFAULT_PRIVACY_POLICY_URL
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.YarnLoader
import blue.anika.wolle.ui.common.stitchedBorder
import blue.anika.wolle.ui.passkey.rememberPasskeyHost

/** How the sign-in page (step 2) signs in: email and password (default) or a pasted token. */
private enum class OnboardingMode {
    MANUAL,
    PASSWORD,
}

/** Step 1 asks where the data lives; step 2 signs in to the chosen server. */
private enum class OnboardingStep {
    WHERE,
    SIGN_IN,
}

@Composable
private fun OnboardingMode.label(): String =
    when (this) {
        OnboardingMode.MANUAL -> stringResource(R.string.onboarding_mode_manual)
        OnboardingMode.PASSWORD -> stringResource(R.string.onboarding_mode_password)
    }

private fun OnboardingMode.icon(): ImageVector =
    when (this) {
        OnboardingMode.MANUAL -> Icons.Filled.Key
        OnboardingMode.PASSWORD -> Icons.Filled.Person
    }

/**
 * Two steps. Step 1 asks where the projects live: the address of a Stricknani server (or a scanned
 * setup code, which carries address and token), or - in builds with the in-app server (T130) - only
 * on this phone. Step 2 signs in to the chosen server: email and password, the server's OIDC
 * providers, a new account when the server allows it, or a pasted token. Every way ends at the same
 * [OnboardingViewModel.connect]/[OnboardingViewModel.persistAndSucceed] destination, validated
 * against the real server ([blue.anika.wolle.data.onboarding.OnboardingValidator]) before anything
 * is saved. Success flips [blue.anika.wolle.data.settings.SettingsRepository.isConfigured], which
 * `MainActivity` observes to swap to the Home-rooted nav graph; this screen doesn't navigate
 * itself.
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
    val passkeysAvailable by viewModel.passkeysAvailable.collectAsStateWithLifecycle()
    val passkeyHost = rememberPasskeyHost()
    val registrationOpen by viewModel.registrationOpen.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WHERE) }
    var mode by rememberSaveable { mutableStateOf(OnboardingMode.PASSWORD) }
    // An error belongs to the page and form that caused it: only a real switch clears it, since the
    // first composition may show an error that just arrived (OIDC).
    var shown by remember { mutableStateOf(step to mode) }
    LaunchedEffect(step, mode) {
        if ((step to mode) != shown) {
            shown = step to mode
            viewModel.clearError()
        }
    }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val isValidating = uiState is OnboardingUiState.Validating
    // The account form only exists while the server says registration is open.
    val registering = creating && registrationOpen == true && mode == OnboardingMode.PASSWORD
    val uriHandler = LocalUriHandler.current

    // Look for sign-in providers (Authelia) and open registration once the address settles.
    LaunchedEffect(serverUrl) { viewModel.onServerUrlChanged(serverUrl) }

    BackHandler(enabled = step == OnboardingStep.SIGN_IN && !isValidating) {
        step = OnboardingStep.WHERE
    }

    // SNA-61: a stricknani://setup URI delivered by the OS (not the in-app scanner) goes through
    // the exact same connectFromScannedText path a real scan result does.
    LaunchedEffect(pendingScannedText) {
        pendingScannedText?.let { text ->
            viewModel.connectFromScannedText(text)
            onScannedTextConsumed()
        }
    }

    val error = (uiState as? OnboardingUiState.Error)?.message

    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding-step",
        ) { shownStep ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (shownStep) {
                    OnboardingStep.WHERE ->
                        WherePage(
                            serverUrl = serverUrl,
                            onServerUrlChange = { serverUrl = it },
                            error = error,
                            enabled = !isValidating,
                            validating = isValidating,
                            onContinue = {
                                serverUrl = withDefaultScheme(serverUrl)
                                if (viewModel.checkServerUrl(serverUrl)) {
                                    step = OnboardingStep.SIGN_IN
                                }
                            },
                            onScan = { showScanner = true },
                            localModeAvailable = viewModel.localModeAvailable,
                            onUseOnThisPhone = viewModel::useOnThisPhone,
                        )
                    OnboardingStep.SIGN_IN ->
                        SignInPage(
                            serverUrl = serverUrl,
                            onBack = { step = OnboardingStep.WHERE },
                            mode = mode,
                            onModeChange = { mode = it },
                            enabled = !isValidating,
                            validating = isValidating,
                            error = error,
                            registrationOpen = registrationOpen,
                            registering = registering,
                            onToggleRegistering = { creating = !creating },
                            oidcProviders = oidcProviders,
                            onOidc = { provider -> viewModel.signInWithOidc(serverUrl, provider) },
                            passkeysAvailable = passkeysAvailable,
                            onPasskey = { viewModel.signInWithPasskey(passkeyHost, serverUrl) },
                            onSubmit = {
                                when (mode) {
                                    OnboardingMode.MANUAL -> viewModel.connect(serverUrl, apiToken)
                                    OnboardingMode.PASSWORD ->
                                        if (registering)
                                            viewModel.register(
                                                serverUrl,
                                                email,
                                                password,
                                                confirmation,
                                            )
                                        else
                                            viewModel.signInWithPassword(serverUrl, email, password)
                                }
                            },
                        ) { shownMode ->
                            when (shownMode) {
                                OnboardingMode.MANUAL ->
                                    ManualOnboardingFields(
                                        apiToken = apiToken,
                                        onApiTokenChange = { apiToken = it },
                                        tokenVisible = tokenVisible,
                                        onTokenVisibleChange = { tokenVisible = it },
                                        enabled = !isValidating,
                                    )
                                OnboardingMode.PASSWORD ->
                                    PasswordOnboardingFields(
                                        email = email,
                                        onEmailChange = { email = it },
                                        password = password,
                                        onPasswordChange = { password = it },
                                        passwordVisible = passwordVisible,
                                        onPasswordVisibleChange = { passwordVisible = it },
                                        confirmation = if (registering) confirmation else null,
                                        onConfirmationChange = { confirmation = it },
                                        enabled = !isValidating,
                                    )
                            }
                        }
                }
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

/** The stitched patch the forms sit on, like the Continue card on Home. */
@Composable
private fun FormPatch(content: @Composable () -> Unit) {
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
            content()
        }
    }
}

@Composable
private fun FormError(error: String?) {
    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun SubmitButton(
    label: String,
    enabled: Boolean,
    validating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        modifier = modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        if (validating) YarnLoader(size = 28.dp, modifier = Modifier.padding(end = 8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Step 1: a server (typed or scanned) or, when this build can, only this phone. */
@Composable
private fun WherePage(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    validating: Boolean,
    onContinue: () -> Unit,
    onScan: () -> Unit,
    localModeAvailable: Boolean,
    onUseOnThisPhone: () -> Unit,
) {
    // The yarn-ball mascot greets first-time users, as on the website's sign-in page.
    Mascot(size = 168.dp, bob = true)
    Text(
        text = stringResource(R.string.onboarding_title),
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(R.string.onboarding_where_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
    FormPatch {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text(stringResource(R.string.onboarding_server_url_label)) },
            placeholder = { Text(stringResource(R.string.onboarding_server_url_placeholder)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
            modifier = Modifier.fillMaxWidth().testTag("e2e-onboarding-server-url"),
        )
        FormError(error)
        SubmitButton(
            label = stringResource(R.string.onboarding_continue_button),
            enabled = enabled,
            validating = validating,
            onClick = onContinue,
            modifier = Modifier.testTag("onboarding-continue"),
        )
        // A setup code from the website carries the address and a token: it skips step 2.
        TextButton(
            onClick = onScan,
            enabled = enabled,
            modifier = Modifier.padding(top = 8.dp).testTag("onboarding-mode-qr"),
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(end = 4.dp),
            )
            Text(stringResource(R.string.onboarding_mode_qr))
        }
    }
    // T130: no server at all; everything stays on this phone.
    if (localModeAvailable) {
        Text(
            text = stringResource(R.string.onboarding_oidc_or),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
        )
        OutlinedButton(
            onClick = onUseOnThisPhone,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            modifier = Modifier.fillMaxWidth().testTag("onboarding-local"),
        ) {
            Icon(PhosphorIcons.CloudSlash, contentDescription = null, Modifier.size(20.dp))
            Text(
                stringResource(R.string.onboarding_local_button),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = stringResource(R.string.onboarding_local_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Step 2: sign in to [serverUrl]; [fields] draws the inputs of the mode being shown. */
@Composable
private fun SignInPage(
    serverUrl: String,
    onBack: () -> Unit,
    mode: OnboardingMode,
    onModeChange: (OnboardingMode) -> Unit,
    enabled: Boolean,
    validating: Boolean,
    error: String?,
    registrationOpen: Boolean?,
    registering: Boolean,
    onToggleRegistering: () -> Unit,
    oidcProviders: List<OidcProvider>,
    onOidc: (OidcProvider) -> Unit,
    passkeysAvailable: Boolean,
    onPasskey: () -> Unit,
    onSubmit: () -> Unit,
    fields: @Composable (OnboardingMode) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        IconButton(
            onClick = onBack,
            enabled = enabled,
            modifier = Modifier.testTag("onboarding-back"),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.onboarding_change_server),
            )
        }
        Mascot(size = 40.dp)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = stringResource(R.string.onboarding_sign_in_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = displayHost(serverUrl),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("onboarding-server"),
            )
        }
    }
    FormPatch {
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding-mode",
        ) { shownMode ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) { fields(shownMode) }
        }
        FormError(error)
        SubmitButton(
            label =
                stringResource(
                    when {
                        mode == OnboardingMode.MANUAL -> R.string.onboarding_connect_button
                        registering -> R.string.onboarding_register_button
                        else -> R.string.onboarding_mode_password
                    }
                ),
            enabled = enabled,
            validating = validating,
            onClick = onSubmit,
        )
        if (mode == OnboardingMode.PASSWORD && registrationOpen == true) {
            TextButton(
                onClick = onToggleRegistering,
                enabled = enabled,
                modifier = Modifier.padding(top = 8.dp).testTag("onboarding-toggle-register"),
            ) {
                Text(
                    stringResource(
                        if (registering) R.string.onboarding_register_back
                        else R.string.onboarding_register_prompt
                    )
                )
            }
        }
        // Only when the server's meta says it has passkeys. Below the email and password form, as
        // a secondary way in (and not while creating an account).
        if (mode == OnboardingMode.PASSWORD && passkeysAvailable && !registering) {
            Text(
                text = stringResource(R.string.onboarding_oidc_or),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedButton(
                onClick = onPasskey,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                modifier =
                    Modifier.fillMaxWidth().padding(top = 12.dp).testTag("onboarding-passkey"),
            ) {
                Icon(
                    PhosphorIcons.LockKey,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    stringResource(R.string.onboarding_passkey_button),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        // Only when the server's meta lists providers. Below the email and password form, as a
        // secondary way in.
        if (mode == OnboardingMode.PASSWORD && oidcProviders.isNotEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_oidc_or),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            oidcProviders.forEach { provider ->
                OutlinedButton(
                    onClick = { onOidc(provider) },
                    enabled = enabled,
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

    // A closed server points people to setting up their own instead.
    if (mode == OnboardingMode.PASSWORD && registrationOpen == false) {
        SelfHostCard(modifier = Modifier.padding(top = 20.dp))
    }

    // Signing in is the friendly default; the token is for people who already have one from the
    // website.
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
                    onClick = { onModeChange(candidate) },
                    enabled = enabled,
                    label = { Text(candidate.label()) },
                    leadingIcon = {
                        Icon(candidate.icon(), contentDescription = null, Modifier.size(18.dp))
                    },
                    modifier = Modifier.testTag("onboarding-mode-${candidate.name.lowercase()}"),
                )
            }
    }
}

/** `wolle.example.com` -> `https://wolle.example.com`; an address with a scheme stays as typed. */
internal fun withDefaultScheme(serverUrl: String): String {
    val trimmed = serverUrl.trim()
    return if (trimmed.isEmpty() || "://" in trimmed) trimmed else "https://$trimmed"
}

/** `https://wolle.example.com/` -> `wolle.example.com`, for the sign-in page's header. */
internal fun displayHost(serverUrl: String): String =
    serverUrl.trim().trimEnd('/').substringAfter("://")

@Composable
private fun ManualOnboardingFields(
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    tokenVisible: Boolean,
    onTokenVisibleChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
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
        modifier = Modifier.fillMaxWidth().testTag("e2e-onboarding-api-token"),
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
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibleChange: (Boolean) -> Unit,
    confirmation: String?,
    onConfirmationChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text(stringResource(R.string.onboarding_email_label)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth().testTag("onboarding-email"),
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

    if (confirmation != null) {
        OutlinedTextField(
            value = confirmation,
            onValueChange = onConfirmationChange,
            label = { Text(stringResource(R.string.onboarding_register_confirm_label)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation =
                if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("onboarding-confirm"),
        )
        Text(
            text = stringResource(R.string.onboarding_register_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
