package blue.anika.wolle.ui.oidc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.oidc.OidcIdentity
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.settings.SettingsGroupCard
import blue.anika.wolle.ui.settings.SettingsListItem

/**
 * "Linked logins" in Settings → Account. Draws nothing unless the server lists at least one sign-in
 * provider in `GET /api/v2/meta`.
 */
@Composable
fun LinkedLoginsCard(viewModel: LinkedLoginsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    if (state.phase == LinkedLoginsPhase.Hidden) return

    SettingsGroupCard(
        title = stringResource(R.string.oidc_linked_logins_title),
        icon = PhosphorIcons.LockKey,
    ) {
        if (state.phase == LinkedLoginsPhase.Failed) {
            SettingsListItem(
                headlineContent = {
                    Text(stringResource(R.string.oidc_linked_logins_load_failed))
                },
                trailingContent = {
                    TextButton(onClick = viewModel::reload) {
                        Text(stringResource(R.string.oidc_linked_logins_retry))
                    }
                },
            )
            return@SettingsGroupCard
        }

        Text(
            stringResource(
                R.string.oidc_linked_logins_hint,
                state.providers.first().name,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (state.identities.isEmpty()) {
            SettingsListItem(
                headlineContent = { Text(stringResource(R.string.oidc_linked_logins_empty)) }
            )
        }
        state.identities.forEach { identity ->
            IdentityRow(
                identity = identity,
                providerName = state.providerName(identity.provider),
                enabled = !state.working,
                onUnlink = { viewModel.onUnlinkClicked(identity) },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.linkable.forEach { provider ->
                Button(
                    onClick = { viewModel.onLinkClicked(provider) },
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth().testTag("oidc-link-${provider.id}"),
                ) {
                    Text(stringResource(R.string.oidc_link_button, provider.name))
                }
            }
        }
    }

    state.confirmUnlink?.let { identity ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUnlink,
            title = { Text(stringResource(R.string.oidc_unlink_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.oidc_unlink_text,
                        state.providerName(identity.provider),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUnlink) {
                    Text(
                        stringResource(R.string.oidc_unlink_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUnlink) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    sheet?.let {
        ReauthSheet(
            state = it,
            onSubmitPassword = viewModel::submitPassword,
            onUseProvider = viewModel::confirmWithProvider,
            onUsePasskey = viewModel::confirmWithPasskey,
            onDismiss = viewModel::dismissSheet,
        )
    }
}

@Composable
private fun IdentityRow(
    identity: OidcIdentity,
    providerName: String,
    enabled: Boolean,
    onUnlink: () -> Unit,
) {
    SettingsListItem(
        headlineContent = { Text(providerName) },
        supportingContent = {
            Column {
                Text(identity.email ?: identity.subjectHint)
                identity.linkedAt?.let {
                    Text(stringResource(R.string.oidc_linked_since, linkedDate(it)))
                }
                if (!identity.canUnlink) {
                    Text(
                        stringResource(R.string.oidc_unlink_only_method),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            OutlinedButton(
                onClick = onUnlink,
                enabled = enabled && identity.canUnlink,
                modifier = Modifier.testTag("oidc-unlink-${identity.id}"),
            ) {
                Text(stringResource(R.string.oidc_unlink_button))
            }
        },
    )
}

/** `2026-11-03T12:00:00.000000Z` as `2026-11-03`; anything unexpected is shown as received. */
internal fun linkedDate(linkedAt: String): String = linkedAt.substringBefore('T')
