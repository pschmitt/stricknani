package blue.anika.wolle.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.passkey.rememberPasskeyHost

/** Signs in again after the server rejected the saved token; nothing on the phone is wiped. */
@Composable
fun SignInAgainScreen(onDone: () -> Unit, viewModel: SignInAgainViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf(viewModel.savedEmail) }
    // Deliberately not saveable: a password never goes into saved instance state.
    var password by remember { mutableStateOf("") }
    val working = state is SignInAgainState.Working
    val passkeysAvailable by viewModel.passkeysAvailable.collectAsStateWithLifecycle()
    val passkeyHost = rememberPasskeyHost()

    LaunchedEffect(state) { if (state is SignInAgainState.Done) onDone() }

    Column(
        modifier =
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.sign_in_again_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(R.string.sign_in_again_text, viewModel.serverUrl),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.onboarding_email_label)) },
            singleLine = true,
            enabled = !working,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("sign-in-again-email"),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.onboarding_password_label)) },
            singleLine = true,
            enabled = !working,
            visualTransformation = PasswordVisualTransformation(),
            // A secret: no suggestions or learning, and adb::rbw-type can tell it is a password.
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("sign-in-again-password"),
        )
        (state as? SignInAgainState.Error)?.let {
            Text(
                it.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sign-in-again-error"),
            )
        }
        Button(
            onClick = { viewModel.signIn(email, password) },
            enabled = !working,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("sign-in-again-submit"),
        ) {
            Text(stringResource(R.string.sign_in_again_button))
        }
        if (passkeysAvailable) {
            Text(
                stringResource(R.string.onboarding_oidc_or),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { viewModel.signInWithPasskey(passkeyHost) },
                enabled = !working,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("sign-in-again-passkey"),
            ) {
                Text(stringResource(R.string.onboarding_passkey_button))
            }
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sign_in_again_not_now))
        }
    }
}
