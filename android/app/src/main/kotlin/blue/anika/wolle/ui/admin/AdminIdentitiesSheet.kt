package blue.anika.wolle.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.UserLabelSize
import blue.anika.wolle.ui.common.UserSentence
import blue.anika.wolle.ui.common.YarnLoader
import blue.anika.wolle.ui.common.accountRef
import blue.anika.wolle.ui.oidc.ReauthSheet
import blue.anika.wolle.ui.oidc.linkedDate

/** An administrator's view of one account's linked logins, with a guarded unlink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminIdentitiesSheet(viewModel: AdminIdentitiesViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reauth by viewModel.sheet.collectAsStateWithLifecycle()
    val user = state.user ?: return

    ModalBottomSheet(onDismissRequest = viewModel::close) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding()
                    .testTag("admin-identities-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UserSentence(
                R.string.oidc_admin_title,
                accountRef(user.email, avatarUrl = user.avatarUrl),
                size = UserLabelSize.Compact,
                style = MaterialTheme.typography.titleLarge,
            )
            when {
                state.loading -> YarnLoader()
                state.failed -> {
                    Text(stringResource(R.string.oidc_linked_logins_load_failed))
                    TextButton(onClick = viewModel::reload) {
                        Text(stringResource(R.string.oidc_linked_logins_retry))
                    }
                }
                state.identities.isEmpty() ->
                    Text(
                        stringResource(R.string.oidc_admin_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else ->
                    state.identities.forEach { identity ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    state.providerName(identity.provider),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    identity.email ?: identity.subjectHint,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                identity.linkedAt?.let {
                                    Text(
                                        stringResource(R.string.oidc_linked_since, linkedDate(it)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!identity.canUnlink) {
                                    Text(
                                        stringResource(R.string.oidc_unlink_only_method),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { viewModel.onUnlinkClicked(identity) },
                                enabled = identity.canUnlink && !state.working,
                                modifier = Modifier.testTag("admin-identity-unlink-${identity.id}"),
                            ) {
                                Text(stringResource(R.string.oidc_unlink_button))
                            }
                        }
                    }
            }
        }
    }

    state.confirmUnlink?.let { identity ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUnlink,
            title = { Text(stringResource(R.string.oidc_unlink_title)) },
            text = {
                UserSentence(
                    R.string.oidc_admin_unlink_text,
                    state.providerName(identity.provider),
                    accountRef(user.email, avatarUrl = user.avatarUrl),
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

    reauth?.let {
        ReauthSheet(
            state = it,
            onSubmitPassword = viewModel::submitPassword,
            onUseProvider = viewModel::confirmWithProvider,
            onUsePasskey = viewModel::confirmWithPasskey,
            onDismiss = viewModel::dismissSheet,
        )
    }
}
