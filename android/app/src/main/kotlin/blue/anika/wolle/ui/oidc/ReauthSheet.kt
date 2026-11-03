package blue.anika.wolle.ui.oidc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.PhosphorIcons

/**
 * "Confirm it's you" before linking or unlinking a login. The password field is a real password
 * field (`KeyboardType.Password`, masked); the typed value stays in this composable's own
 * `remember` state - not saveable, so it is never written to a saved-state bundle - and leaves it
 * only through [onSubmitPassword].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReauthSheet(
    state: ReauthUiState,
    onSubmitPassword: (String) -> Unit,
    onUseProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    val request = state.request
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = { if (!state.busy) onDismiss() }) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .testTag("oidc-reauth-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                PhosphorIcons.LockKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(
                stringResource(R.string.oidc_reauth_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.oidc_reauth_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (request.canUsePassword) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.oidc_reauth_password_label)) },
                    singleLine = true,
                    enabled = !state.busy,
                    isError = state.error != null,
                    visualTransformation =
                        if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription =
                                    stringResource(
                                        if (visible) R.string.onboarding_hide_password
                                        else R.string.onboarding_show_password
                                    ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("oidc-reauth-password"),
                )
            }
            state.error?.let { failure ->
                Text(
                    failure.message(context, request.oidcProvider?.name ?: DEFAULT_PROVIDER_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (request.canUsePassword) {
                Button(
                    onClick = {
                        onSubmitPassword(password)
                        password = ""
                    },
                    enabled = !state.busy && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("oidc-reauth-continue"),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 0.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        stringResource(R.string.oidc_reauth_continue),
                        modifier = Modifier.padding(start = if (state.busy) 8.dp else 0.dp),
                    )
                }
            }
            request.oidcProvider?.let { provider ->
                TextButton(
                    onClick = onUseProvider,
                    enabled = !state.busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.oidc_reauth_with_provider, provider.name))
                }
            }
        }
    }
}
