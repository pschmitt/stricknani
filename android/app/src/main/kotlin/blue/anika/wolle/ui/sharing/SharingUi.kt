package blue.anika.wolle.ui.sharing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.PersonDto
import blue.anika.wolle.data.api.dto.ShareDto
import blue.anika.wolle.data.repository.ShareRole
import blue.anika.wolle.data.repository.SharingError
import blue.anika.wolle.ui.common.AccountAvatar
import blue.anika.wolle.ui.common.LocalOwnerContext
import blue.anika.wolle.ui.common.PhosphorIcons

/** Maximum avatars on the Share row before "+N". */
private const val MAX_STACKED_AVATARS = 4

@Composable
internal fun roleLabel(role: ShareRole): String =
    stringResource(
        if (role == ShareRole.Editor) R.string.sharing_role_editor else R.string.sharing_role_viewer
    )

@Composable internal fun roleLabel(wire: String): String = roleLabel(ShareRole.fromWire(wire))

@Composable
private fun errorText(error: SharingError): String =
    stringResource(
        when (error) {
            SharingError.Offline -> R.string.sharing_offline
            SharingError.Forbidden -> R.string.sharing_error_forbidden
            SharingError.NotFound -> R.string.sharing_error_not_found
            SharingError.Conflict -> R.string.sharing_error_conflict
            SharingError.Invalid -> R.string.sharing_error_invalid
            SharingError.Other -> R.string.sharing_error_other
        }
    )

@Composable
private fun PersonAvatar(person: PersonDto, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val resolve = LocalOwnerContext.current.resolveUrl
    AccountAvatar(url = resolve(person.avatarUrl), email = person.name, size = size)
}

/**
 * The "Share" row on a project's or yarn's detail screen for its owner or an administrator: the
 * Share action with the avatars of the people it is shared with. Opens [SharingSheet].
 */
@Composable
fun ShareRow(state: SharingUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val names = state.shares.joinToString { it.user.name }
    val description =
        if (state.shares.isEmpty()) null
        else stringResource(R.string.sharing_avatars_description, names)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("share-row"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                PhosphorIcons.UsersThree,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(R.string.sharing_share),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy((-8).dp),
                modifier = Modifier.semantics { description?.let { contentDescription = it } },
            ) {
                state.shares.take(MAX_STACKED_AVATARS).forEach { PersonAvatar(it.user, 28.dp) }
                val more = state.shares.size - MAX_STACKED_AVATARS
                if (more > 0) {
                    Text(
                        "+$more",
                        style = MaterialTheme.typography.labelMedium,
                        modifier =
                            Modifier.padding(start = 12.dp).align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

/**
 * The Share sheet: the owner on top, each person with a role choice and a remove action, then a
 * searchable people picker, and the quiet Transfer action at the bottom. Needs the network; when
 * offline everything is disabled with a short explanation (sharing is never queued).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingSheet(
    controller: SharingController,
    isProject: Boolean,
    linkedYarnCount: Int,
    onDismiss: () -> Unit,
    onTransfer: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val search by controller.people.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { controller.open() }
    val enabled = !state.offline
    val sharedIds = state.shares.map { it.user.id }.toSet()
    val candidates = search.visiblePeople.filter { it.id !in sharedIds && it.id != state.owner?.id }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().testTag("sharing-sheet"),
        ) {
            item {
                Text(
                    stringResource(R.string.sharing_share),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (state.offline) {
                item {
                    Text(
                        stringResource(R.string.sharing_offline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("sharing-offline"),
                    )
                }
            }
            state.error
                ?.takeIf { it != SharingError.Offline }
                ?.let { error ->
                    item {
                        Text(
                            errorText(error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp).testTag("sharing-error"),
                        )
                    }
                }
            if (isProject) {
                item {
                    NeutralNote(
                        if (linkedYarnCount > 0)
                            pluralStringResource(
                                R.plurals.sharing_linked_yarns_note,
                                linkedYarnCount,
                                linkedYarnCount,
                            )
                        else stringResource(R.string.sharing_linked_yarns_note_none),
                        Modifier.padding(bottom = 8.dp).testTag("sharing-yarn-note"),
                    )
                }
            }
            state.owner?.let { owner ->
                item(key = "owner") {
                    PersonRow(owner) {
                        Text(
                            stringResource(R.string.sharing_owner),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (state.phase) {
                SharingPhase.Loading ->
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                SharingPhase.Failed ->
                    if (!state.offline) {
                        item(key = "failed") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.sharing_load_failed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = controller::reload) {
                                    Text(stringResource(R.string.account_tokens_retry))
                                }
                            }
                        }
                    }
                else ->
                    if (state.shares.isEmpty() && state.phase == SharingPhase.Ready) {
                        item(key = "nobody") {
                            Text(
                                stringResource(R.string.sharing_nobody_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
            }
            items(state.shares, key = { "share-${it.user.id}" }) { share ->
                ShareRowItem(
                    share = share,
                    enabled = enabled && share.user.id !in state.busy,
                    onRole = { controller.setRole(share.user.id, it) },
                    onRemove = { controller.remove(share.user.id) },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text(
                    stringResource(R.string.sharing_share_with),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    ShareRole.entries.forEach { role ->
                        FilterChip(
                            selected = state.newRole == role,
                            onClick = { controller.setNewRole(role) },
                            enabled = enabled,
                            label = { Text(roleLabel(role)) },
                            modifier = Modifier.testTag("new-role-${role.wire}"),
                        )
                    }
                }
            }
            val picked = state.selected
            if (picked != null) {
                // Once somebody is picked the list collapses to them, with a way to change it.
                item(key = "picked") {
                    PersonRow(picked, Modifier.testTag("picked-person")) {
                        TextButton(onClick = { controller.select(null) }, enabled = enabled) {
                            Text(stringResource(R.string.sharing_change))
                        }
                    }
                }
                item(key = "share-picked") {
                    Button(
                        onClick = controller::shareSelected,
                        enabled = enabled && picked.id !in state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("share-picked"),
                    ) {
                        Text(stringResource(R.string.sharing_share))
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = controller.people::setQuery,
                        enabled = enabled,
                        placeholder = { Text(stringResource(R.string.sharing_people_search)) },
                        leadingIcon = {
                            Icon(PhosphorIcons.MagnifyingGlass, contentDescription = null)
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().testTag("people-search"),
                    )
                }
                items(candidates, key = { "person-${it.id}" }) { person ->
                    PersonRow(
                        person = person,
                        modifier =
                            Modifier.clickable(
                                enabled = enabled,
                                onClick = { controller.select(person) },
                            ),
                    ) {}
                }
            }
            if (picked == null && !state.offline && search.people.isNotEmpty() && search.hasMore) {
                item(key = "more") {
                    LaunchedEffect(search.page, search.query) { controller.people.loadMore() }
                }
            }
            if (
                picked == null &&
                    !state.offline &&
                    !search.loading &&
                    search.resultsFor == search.query &&
                    candidates.isEmpty() &&
                    !search.failed
            ) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.sharing_people_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                TextButton(
                    onClick = onTransfer,
                    enabled = enabled,
                    modifier = Modifier.testTag("transfer-action"),
                ) {
                    Text(
                        stringResource(R.string.sharing_transfer),
                        color =
                            if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            item { Box(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun PersonRow(
    person: PersonDto,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
    ) {
        PersonAvatar(person)
        Text(
            person.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
private fun ShareRowItem(
    share: ShareDto,
    enabled: Boolean,
    onRole: (ShareRole) -> Unit,
    onRemove: () -> Unit,
) {
    PersonRow(share.user, Modifier.testTag("share-${share.user.id}")) {
        var open by remember { mutableStateOf(false) }
        val roleDescription = stringResource(R.string.sharing_role_of, share.user.name)
        Box {
            TextButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = roleDescription },
            ) {
                Text(roleLabel(share.role))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ShareRole.entries.forEach { role ->
                    DropdownMenuItem(
                        text = { Text(roleLabel(role)) },
                        onClick = {
                            open = false
                            if (role.wire != share.role) onRole(role)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                PhosphorIcons.X,
                contentDescription =
                    stringResource(R.string.sharing_remove_description, share.user.name),
            )
        }
    }
}

/**
 * The transfer confirmation: pick the person, see that it applies immediately and that the caller
 * stays an editor, and (projects with yarns the owner owns) optionally move those yarns along. The
 * confirm button names the person.
 */
@Composable
fun TransferDialog(
    controller: SharingController,
    ownedLinkedYarnCount: Int,
    offline: Boolean,
    onDismiss: () -> Unit,
) {
    val transfer by controller.transfer.collectAsStateWithLifecycle()
    val search by controller.transferPeople.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = { if (!transfer.submitting) onDismiss() },
        title = { Text(stringResource(R.string.sharing_transfer_title)) },
        text = {
            Column(Modifier.testTag("transfer-dialog")) {
                Text(
                    stringResource(R.string.sharing_transfer_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (offline) {
                    Text(
                        stringResource(R.string.sharing_offline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val chosen = transfer.selected
                if (chosen != null) {
                    // Collapsed to the chosen person, with a way to change it.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(top = 12.dp)
                                .testTag("transfer-picked"),
                    ) {
                        PersonAvatar(chosen, 32.dp)
                        Text(
                            chosen.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { controller.selectNewOwner(null) },
                            enabled = !transfer.submitting,
                        ) {
                            Text(stringResource(R.string.sharing_change))
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = search.query,
                        onValueChange = controller.transferPeople::setQuery,
                        enabled = !offline,
                        placeholder = { Text(stringResource(R.string.sharing_transfer_pick)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        items(search.visiblePeople, key = { it.id }) { person ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable(
                                            enabled = !offline,
                                            role = Role.Button,
                                            onClick = { controller.selectNewOwner(person) },
                                        ),
                            ) {
                                PersonAvatar(person, 32.dp)
                                Text(
                                    person.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                if (ownedLinkedYarnCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = transfer.moveLinkedYarns,
                                    enabled = !transfer.submitting,
                                    role = Role.Checkbox,
                                    onValueChange = controller::setMoveLinkedYarns,
                                )
                                .testTag("transfer-move-yarns"),
                    ) {
                        Checkbox(checked = transfer.moveLinkedYarns, onCheckedChange = null)
                        Text(
                            pluralStringResource(
                                R.plurals.sharing_transfer_move_yarns,
                                ownedLinkedYarnCount,
                                ownedLinkedYarnCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                transfer.error
                    ?.takeIf { it != SharingError.Offline }
                    ?.let {
                        Text(
                            errorText(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
            }
        },
        confirmButton = {
            val target = transfer.selected
            Button(
                onClick = controller::confirmTransfer,
                enabled = target != null && !offline && !transfer.submitting,
                modifier = Modifier.testTag("transfer-confirm"),
            ) {
                Text(
                    if (target != null)
                        stringResource(R.string.sharing_transfer_confirm, target.name)
                    else stringResource(R.string.sharing_transfer)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !transfer.submitting) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** "Leave" confirmation for a recipient; the destructive action names itself like the button. */
@Composable
fun LeaveDialog(itemName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sharing_leave_title, itemName)) },
        text = { Text(stringResource(R.string.sharing_leave_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("leave-confirm")) {
                Text(
                    stringResource(R.string.sharing_leave),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Which of the sharing dialogs of a detail screen is open. */
class SharingDialogs {
    var sheet by mutableStateOf(false)
    var transfer by mutableStateOf(false)
    var leave by mutableStateOf(false)
}

@Composable fun rememberSharingDialogs(): SharingDialogs = remember { SharingDialogs() }

/**
 * The Share sheet, Transfer dialog and Leave confirmation of a detail screen, driven by [dialogs].
 * A transfer that went through closes the sheet and the dialog; the cache was re-read, so the
 * screen already shows the caller as an editor.
 */
@Composable
fun SharingDialogHost(
    dialogs: SharingDialogs,
    controller: SharingController,
    isProject: Boolean,
    itemName: String,
    linkedYarnCount: Int,
    ownedLinkedYarnCount: Int,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val transfer by controller.transfer.collectAsStateWithLifecycle()
    LaunchedEffect(transfer.done) {
        if (transfer.done) {
            dialogs.transfer = false
            dialogs.sheet = false
        }
    }
    if (dialogs.sheet) {
        SharingSheet(
            controller = controller,
            isProject = isProject,
            linkedYarnCount = linkedYarnCount,
            onDismiss = { dialogs.sheet = false },
            onTransfer = {
                controller.openTransfer()
                dialogs.transfer = true
            },
        )
    }
    if (dialogs.transfer) {
        TransferDialog(
            controller = controller,
            ownedLinkedYarnCount = if (isProject) ownedLinkedYarnCount else 0,
            offline = state.offline,
            onDismiss = { dialogs.transfer = false },
        )
    }
    if (dialogs.leave) {
        LeaveDialog(
            itemName = itemName,
            onConfirm = {
                dialogs.leave = false
                controller.leave()
            },
            onDismiss = { dialogs.leave = false },
        )
    }
}

/**
 * An informational note on a neutral surface: the same in light and dark, never an error or
 * tertiary colour, so it does not read as a warning.
 */
@Composable
private fun NeutralNote(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
