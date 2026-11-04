package blue.anika.wolle.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiGeneration
import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.PrivilegedState
import blue.anika.wolle.data.update.UpdateChannel
import blue.anika.wolle.data.update.UpdateInstallState
import blue.anika.wolle.data.update.canInstallInApp
import blue.anika.wolle.data.update.inAppUpdateAvailable
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.common.mascot.MascotCue
import blue.anika.wolle.ui.common.mascot.MascotCueKind
import blue.anika.wolle.ui.common.privacyPolicyUrl
import blue.anika.wolle.ui.update.UpdateFailureActions
import blue.anika.wolle.ui.update.UpdateInstallEffects
import blue.anika.wolle.ui.update.UpdateInstallProgress
import blue.anika.wolle.ui.update.UpdateInstallViewModel
import blue.anika.wolle.ui.update.updateInstallStatusText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

private const val PLAY_URL = "https://play.google.com/store/apps/details?id=blue.anika.wolle"
private const val GITHUB_URL = "https://github.com/pschmitt/stricknani"
private const val SPONSOR_URL = "https://github.com/sponsors/pschmitt"
private const val LICENSE_URL = "https://github.com/pschmitt/stricknani/blob/main/LICENSE"
private const val ISSUES_URL = "https://github.com/pschmitt/stricknani/issues"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSettingsScreen(
    onBack: () -> Unit,
    onShowLibraries: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val serverMeta by viewModel.serverMeta.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val haptics = LocalHapticFeedback.current
    val toastHolder = remember { arrayOfNulls<Toast>(1) }
    var mascotCue by remember { mutableStateOf<MascotCue?>(null) }
    val developerMode by viewModel.developerMode.collectAsStateWithLifecycle()
    var obtainiumMissing by remember { mutableStateOf(false) }
    fun addToObtainium(rolling: Boolean) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(ObtainiumLink.build(rolling)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            obtainiumMissing = true
        }
    }
    if (obtainiumMissing) {
        AlertDialog(
            onDismissRequest = { obtainiumMissing = false },
            title = { Text(stringResource(R.string.developer_obtainium_missing_title)) },
            text = { Text(stringResource(R.string.developer_obtainium_missing_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        obtainiumMissing = false
                        uriHandler.openUri(ObtainiumLink.OBTAINIUM_RELEASES_URL)
                    }
                ) {
                    Text(stringResource(R.string.developer_obtainium_missing_get))
                }
            },
            dismissButton = {
                TextButton(onClick = { obtainiumMissing = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    val apiState by viewModel.apiState.collectAsStateWithLifecycle()
    val pendingWrites by viewModel.pendingWrites.collectAsStateWithLifecycle()
    val lastSyncedMillis by viewModel.lastSyncedMillis.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.developerModeEvents.collect { event ->
            // Toasts are announced politely by the platform; replace the previous one so a
            // countdown never queues up behind itself.
            toastHolder[0]?.cancel()
            toastHolder[0] =
                Toast.makeText(context, event.message, Toast.LENGTH_SHORT).also { it.show() }
            if (event.unlocked) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                mascotCue = MascotCue(MascotCueKind.Happy)
            }
        }
    }
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val updateChannel by viewModel.updateChannel.collectAsStateWithLifecycle()
    val installViewModel: UpdateInstallViewModel = hiltViewModel()
    val installState by installViewModel.state.collectAsStateWithLifecycle()
    val autoUpdate by installViewModel.autoUpdate.collectAsStateWithLifecycle()
    val notifyUpdates by installViewModel.notifyUpdates.collectAsStateWithLifecycle()
    val shizukuState by installViewModel.shizukuState.collectAsStateWithLifecycle()
    val shizukuEnabled by installViewModel.shizukuEnabled.collectAsStateWithLifecycle()
    val silentPossible by installViewModel.silentInstallPossible.collectAsStateWithLifecycle()
    val installDeveloperMode by installViewModel.developerMode.collectAsStateWithLifecycle()
    // Starting Shizuku or allowing the app happens elsewhere; re-read the state on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { installViewModel.refreshShizuku() }
    UpdateInstallEffects(installViewModel, installState)
    LaunchedEffect(Unit) { viewModel.checkForUpdate() }
    LaunchedEffect(mascotCue) {
        if (mascotCue != null) {
            delay(4_000)
            mascotCue = null
        }
    }

    val unavailable = stringResource(R.string.about_settings_server_version_unavailable)
    val serverVersion = serverMeta?.let { "${it.version} (${it.buildId})" } ?: unavailable
    val appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val revision = BuildRevision.parse(BuildConfig.GIT_REVISION)
    val build = revision?.display
    val copyText = buildString {
        append("Stricknani $appVersion")
        if (build != null) append("\nBuild: $build")
        serverMeta?.let { append("\nServer: ${it.version} (${it.buildId})") }
    }
    val copiedMessage = stringResource(R.string.about_copied)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(SettingsCategory.About.title()) },
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
            item { AboutHero(mascotCue) }
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_settings_version_title),
                    icon = PhosphorIcons.Info,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_app_version_label))
                        },
                        supportingContent = {
                            Text(appVersion)
                        },
                    )
                    AboutUpdateRow(
                        state = updateState,
                        channel = updateChannel,
                        install = installState,
                        onDownload = {
                            // Release APKs install themselves; debug builds (another package id)
                            // keep the browser download.
                            if (it.canInstallInApp()) installViewModel.start(it)
                            else uriHandler.openUri(it.downloadUrl)
                        },
                        onTryAgain = {
                            installViewModel.dismissFailure()
                            (updateState as? AboutUpdateState.Available)
                                ?.update
                                ?.let(installViewModel::start)
                        },
                        onOpenBrowser = {
                            installViewModel.dismissFailure()
                            (updateState as? AboutUpdateState.Available)?.update?.let {
                                uriHandler.openUri(it.downloadUrl)
                            }
                        },
                        onRetry = viewModel::checkForUpdate,
                        onOpenPlay = { uriHandler.openUri(PLAY_URL) },
                    )
                    if (BuildConfig.SELF_UPDATE_ENABLED && installDeveloperMode) {
                        UpdateChannelRow(
                            selected = updateChannel,
                            onSelect = viewModel::setUpdateChannel,
                        )
                    }
                    val shizukuUi =
                        shizukuToggleUi(
                            developerMode = installDeveloperMode,
                            supported = installViewModel.shizukuSupported,
                            preferenceOn = shizukuEnabled,
                            state = shizukuState,
                        )
                    if (shizukuUi.visible) {
                        ShizukuInstallRow(
                            ui = shizukuUi,
                            onChange = installViewModel::setShizukuEnabled,
                            onAllow = installViewModel::requestShizukuPermission,
                            onOpenShizuku = { openShizuku(context, installViewModel) },
                        )
                    }
                    if (inAppUpdateAvailable) {
                        AutoUpdateRow(
                            checked = autoUpdate,
                            silentPossible = silentPossible,
                            onChange = installViewModel::setAutoUpdate,
                        )
                    }
                    if (inAppUpdateAvailable) {
                        NotifyUpdatesRow(
                            checked = notifyUpdates,
                            onChange = installViewModel::setNotifyUpdates,
                        )
                    }
                    val buildHint = stringResource(R.string.about_settings_build_hint)
                    SettingsListItem(
                        modifier =
                            Modifier.clickable(
                                onClickLabel = buildHint,
                                role = Role.Button,
                                onClick = viewModel::onBuildRowTap,
                            ),
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_build_label))
                        },
                        supportingContent = {
                            Text(build ?: stringResource(R.string.about_settings_build_unknown))
                        },
                        trailingContent =
                            revision?.let {
                                {
                                    IconButton(onClick = { uriHandler.openUri(it.commitUrl) }) {
                                        Icon(
                                            PhosphorIcons.GithubLogo,
                                            contentDescription =
                                                stringResource(R.string.about_open_commit),
                                        )
                                    }
                                }
                            },
                    )
                    if (serverMeta != null)
                        SettingsListItem(
                            headlineContent = {
                                Text(stringResource(R.string.about_settings_server_version_label))
                            },
                            supportingContent = { Text(serverVersion) },
                        )
                    TextButton(
                        onClick = {
                            copyToClipboard(context, copyText, copiedMessage)
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 8.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.about_copy_button),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            item {
                val motion = !animationsDisabled()
                AnimatedVisibility(
                    visible = developerMode,
                    enter = if (motion) fadeIn() + expandVertically() else fadeIn(snap()),
                    exit = if (motion) fadeOut() + shrinkVertically() else fadeOut(snap()),
                ) {
                    DeveloperCard(
                        serverUrl = serverUrl,
                        serverMeta = serverMeta,
                        apiState = apiState,
                        lastSyncedMillis = lastSyncedMillis,
                        pendingWrites = pendingWrites,
                        revision = revision,
                        onDeveloperModeChange = viewModel::setDeveloperMode,
                        onAddToObtainium = { rolling -> addToObtainium(rolling) },
                        onReinstallViaShizuku =
                            if (
                                installViewModel.shizukuSupported &&
                                    shizukuState == PrivilegedState.READY
                            ) {
                                installViewModel::reinstallThisBuild
                            } else null,
                        onTestUpdateNotification =
                            if (BuildConfig.SELF_UPDATE_ENABLED) {
                                installViewModel::postTestUpdateNotification
                            } else null,
                    )
                }
            }
            item {
                SettingsSingleItemCard {
                    AboutLinkRow(
                        icon = Icons.Filled.Code,
                        title = stringResource(R.string.about_link_source_title),
                        description = stringResource(R.string.about_link_source_description),
                        onClick = { uriHandler.openUri(GITHUB_URL) },
                    )
                    AboutLinkRow(
                        icon = PhosphorIcons.Heart,
                        title = stringResource(R.string.about_link_sponsor_title),
                        description = stringResource(R.string.about_link_sponsor_description),
                        warm = true,
                        onClick = { uriHandler.openUri(SPONSOR_URL) },
                    )
                    AboutLinkRow(
                        icon = PhosphorIcons.ShieldCheck,
                        title = stringResource(R.string.about_settings_privacy_label),
                        description = stringResource(R.string.about_settings_privacy_description),
                        chevron = true,
                        onClick = { uriHandler.openUri(privacyPolicyUrl(serverUrl)) },
                    )
                    AboutLinkRow(
                        icon = Icons.Filled.BugReport,
                        title = stringResource(R.string.about_link_issues_title),
                        description = stringResource(R.string.about_link_issues_description),
                        onClick = { uriHandler.openUri(ISSUES_URL) },
                    )
                }
            }
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_credits_title),
                    icon = PhosphorIcons.Sparkle,
                ) {
                    AboutCreditRow(
                        title = stringResource(R.string.about_credits_license_prefix),
                        detail = null,
                        onClick = { uriHandler.openUri(LICENSE_URL) },
                    )
                    AboutCreditRow(
                        title = stringResource(R.string.about_credit_icons_title),
                        detail = stringResource(R.string.about_credit_icons_detail),
                        onClick = { uriHandler.openUri("https://phosphoricons.com") },
                    )
                    AboutCreditRow(
                        title = stringResource(R.string.about_credit_display_title),
                        detail = stringResource(R.string.about_credit_display_detail),
                        onClick = {
                            uriHandler.openUri("https://github.com/undercasetype/Fraunces")
                        },
                    )
                    AboutCreditRow(
                        title = stringResource(R.string.about_credit_text_title),
                        detail = stringResource(R.string.about_credit_text_detail),
                        onClick = { uriHandler.openUri("https://github.com/googlefonts/nunito") },
                    )
                    SettingsListItem(
                        modifier =
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.libraries_title),
                                role = Role.Button,
                                onClick = onShowLibraries,
                            ),
                        leadingContent = {
                            Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null)
                        },
                        headlineContent = { Text(stringResource(R.string.libraries_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.about_settings_libraries_description))
                        },
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** Mascot (120dp, gentle 2.4s float of +-4dp unless animations are off), name and tagline. */
@Composable
private fun AboutHero(cue: MascotCue?) {
    val still = animationsDisabled()
    val transition = rememberInfiniteTransition(label = "about-mascot")
    val bob by
        transition.animateFloat(
            initialValue = -4f,
            targetValue = 4f,
            animationSpec =
                infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "about-mascot-bob",
        )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.graphicsLayer { if (!still) translationY = bob * density }) {
            Mascot(size = 120.dp, cue = cue)
        }
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Update status right under the app version. The download button opens the same URL as the Home
 * update card; nothing is claimed for builds that cannot be compared against a release.
 */
@Composable
private fun AboutUpdateRow(
    state: AboutUpdateState,
    channel: UpdateChannel,
    install: UpdateInstallState,
    onDownload: (AppUpdate) -> Unit,
    onTryAgain: () -> Unit,
    onOpenBrowser: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlay: () -> Unit,
) {
    // An install in flight (or just failed) takes over the row of the update it belongs to.
    val installing = state is AboutUpdateState.Available && install.isBusy
    val failed =
        (install as? UpdateInstallState.Failed)?.takeIf { state is AboutUpdateState.Available }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SettingsListItem(
        modifier = Modifier.testTag("about-update-row"),
        leadingContent = {
            when (state) {
                AboutUpdateState.Checking ->
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                AboutUpdateState.UpToDate,
                AboutUpdateState.AheadOfStable ->
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                is AboutUpdateState.Available ->
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                AboutUpdateState.Failed ->
                    Icon(Icons.Filled.CloudOff, contentDescription = null, tint = muted)
                AboutUpdateState.DevBuild,
                AboutUpdateState.Play ->
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = muted)
            }
        },
        supportingContent =
            when {
                installing -> {
                    { UpdateInstallProgress(install, Modifier.padding(top = 8.dp)) }
                }
                failed != null -> {
                    {
                        UpdateFailureActions(failed.failure, onTryAgain, onOpenBrowser)
                    }
                }
                state is AboutUpdateState.Available && !state.update.canInstallInApp() -> {
                    { Text(stringResource(R.string.about_update_browser_hint)) }
                }
                else -> null
            },
        headlineContent = {
            Text(
                updateInstallStatusText(install)?.takeIf { installing || failed != null }
                    ?: when (state) {
                        AboutUpdateState.Checking -> stringResource(R.string.about_update_checking)
                        AboutUpdateState.UpToDate ->
                            stringResource(
                                R.string.about_update_up_to_date,
                                stringResource(channel.labelRes()),
                            )
                        AboutUpdateState.AheadOfStable ->
                            stringResource(R.string.about_update_ahead_of_stable)
                        is AboutUpdateState.Available ->
                            stringResource(
                                when (channel) {
                                    UpdateChannel.STABLE -> R.string.about_update_available_stable
                                    UpdateChannel.LATEST -> R.string.about_update_available_latest
                                },
                                state.update.version,
                            )
                        AboutUpdateState.Failed -> stringResource(R.string.about_update_failed)
                        AboutUpdateState.DevBuild -> stringResource(R.string.about_update_dev_build)
                        AboutUpdateState.Play -> stringResource(R.string.about_update_play)
                    }
            )
        },
        trailingContent = {
            when (state) {
                is AboutUpdateState.Available ->
                    if (!installing && failed == null)
                        Button(onClick = { onDownload(state.update) }) {
                            Text(stringResource(R.string.about_update_button))
                        }
                AboutUpdateState.UpToDate,
                AboutUpdateState.AheadOfStable ->
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.about_update_check))
                    }
                AboutUpdateState.Failed ->
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.about_update_check_again))
                    }
                AboutUpdateState.Play ->
                    TextButton(onClick = onOpenPlay) {
                        Text(stringResource(R.string.about_update_open_play))
                    }
                AboutUpdateState.Checking,
                AboutUpdateState.DevBuild -> Unit
            }
        },
    )
}

@StringRes
private fun UpdateChannel.labelRes(): Int =
    when (this) {
        UpdateChannel.STABLE -> R.string.about_update_channel_stable
        UpdateChannel.LATEST -> R.string.about_update_channel_latest
    }

/** Stable (tagged releases) or Latest (the rolling build of main); GitHub builds only. */
@Composable
private fun UpdateChannelRow(selected: UpdateChannel, onSelect: (UpdateChannel) -> Unit) {
    SettingsListItem(
        modifier = Modifier.testTag("about-update-channel-row"),
        headlineContent = { Text(stringResource(R.string.about_update_channel_title)) },
        supportingContent = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateChannel.entries.forEach { channel ->
                        FilterChip(
                            selected = channel == selected,
                            onClick = { onSelect(channel) },
                            label = { Text(stringResource(channel.labelRes())) },
                            modifier = Modifier.testTag("about-update-channel-${channel.name}"),
                        )
                    }
                }
                Text(
                    stringResource(
                        when (selected) {
                            UpdateChannel.STABLE -> R.string.about_update_channel_stable_help
                            UpdateChannel.LATEST -> R.string.about_update_channel_latest_help
                        }
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
    )
}

private fun openShizuku(context: Context, viewModel: UpdateInstallViewModel) {
    val intent = viewModel.shizukuManagerIntent() ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The manager vanished between the check and the tap; nothing to open.
    }
}

/**
 * "Install updates silently with Shizuku" (developer mode): one switch plus the state it is in. The
 * switch is disabled, and off, whenever Shizuku is missing, stopped or has denied the app.
 */
@Composable
private fun ShizukuInstallRow(
    ui: ShizukuToggleUi,
    onChange: (Boolean) -> Unit,
    onAllow: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("about-shizuku-row")
                .toggleable(
                    value = ui.checked,
                    enabled = ui.enabled,
                    role = Role.Switch,
                    onValueChange = onChange,
                ),
        headlineContent = { Text(stringResource(R.string.about_shizuku_title)) },
        supportingContent = {
            Column {
                Text(
                    stringResource(
                        when (ui.hint) {
                            ShizukuHint.READY -> R.string.about_shizuku_state_ready
                            ShizukuHint.PERMISSION_NEEDED -> R.string.about_shizuku_state_permission
                            ShizukuHint.PERMISSION_DENIED -> R.string.about_shizuku_state_denied
                            ShizukuHint.NOT_RUNNING -> R.string.about_shizuku_state_not_running
                            else -> R.string.about_shizuku_state_not_installed
                        }
                    ),
                    modifier = Modifier.testTag("about-shizuku-state"),
                )
                when (ui.hint) {
                    ShizukuHint.PERMISSION_NEEDED ->
                        TextButton(onClick = onAllow) {
                            Text(stringResource(R.string.about_shizuku_allow))
                        }
                    ShizukuHint.PERMISSION_DENIED,
                    ShizukuHint.NOT_RUNNING ->
                        TextButton(onClick = onOpenShizuku) {
                            Text(stringResource(R.string.about_shizuku_open))
                        }
                    else -> Unit
                }
            }
        },
        trailingContent = {
            Switch(checked = ui.checked, enabled = ui.enabled, onCheckedChange = null)
        },
    )
}

/** "Update automatically" (GitHub APK builds): background check plus install while idle. */
@Composable
private fun AutoUpdateRow(checked: Boolean, silentPossible: Boolean, onChange: (Boolean) -> Unit) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("about-auto-update-row")
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(stringResource(R.string.about_auto_update_title)) },
        supportingContent = {
            Text(
                stringResource(
                    if (silentPossible) R.string.about_auto_update_help
                    else R.string.about_auto_update_help_first
                )
            )
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** "Notify me about app updates" (GitHub APK builds): at most one notification a day. */
@Composable
private fun NotifyUpdatesRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("about-notify-updates-row")
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(stringResource(R.string.about_notify_updates_title)) },
        supportingContent = { Text(stringResource(R.string.about_notify_updates_help)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** A link row: icon chip, label and a short description; opens in the browser. */
@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    warm: Boolean = false,
    chevron: Boolean = false,
) {
    val chipColor =
        if (warm) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.primaryContainer
    val iconColor =
        if (warm) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer
    SettingsListItem(
        modifier =
            Modifier.heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.about_open_in_browser),
                    role = Role.Button,
                    onClick = onClick,
                ),
        leadingContent = {
            Surface(shape = CircleShape, color = chipColor, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Icon(
                if (chevron) Icons.AutoMirrored.Filled.KeyboardArrowRight
                else PhosphorIcons.ArrowSquareOut,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

private fun copyToClipboard(context: Context, text: String, confirmation: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Stricknani", text))
    Toast.makeText(context, confirmation, Toast.LENGTH_LONG).show()
}

/** A credit or licence line that opens its project page or licence text. */
@Composable
private fun AboutCreditRow(title: String, detail: String?, onClick: () -> Unit) {
    SettingsListItem(
        modifier =
            Modifier.heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(R.string.about_open_in_browser),
                    role = Role.Button,
                    onClick = onClick,
                ),
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
    )
}

/**
 * Debugging facts, revealed by the Build row easter egg. The switch is the only control; every row
 * below it is read-only.
 */
@Composable
private fun DeveloperCard(
    serverUrl: String,
    serverMeta: blue.anika.wolle.data.api.dto.MetaDto?,
    apiState: blue.anika.wolle.data.api.ApiCapabilitiesState?,
    lastSyncedMillis: Long?,
    pendingWrites: Int,
    revision: BuildRevision?,
    onDeveloperModeChange: (Boolean) -> Unit,
    onAddToObtainium: (rolling: Boolean) -> Unit,
    onReinstallViaShizuku: (() -> Unit)?,
    onTestUpdateNotification: (() -> Unit)?,
) {
    val unknown = stringResource(R.string.developer_card_unknown)
    SettingsGroupCard(
        title = stringResource(R.string.developer_card_title),
        icon = Icons.Filled.Code,
    ) {
        val switchLabel = stringResource(R.string.developer_card_switch)
        SettingsListItem(
            modifier =
                Modifier.toggleable(
                    value = true,
                    role = Role.Switch,
                    onValueChange = onDeveloperModeChange,
                ),
            headlineContent = { Text(switchLabel) },
            trailingContent = { Switch(checked = true, onCheckedChange = null) },
        )
        DeveloperRow(
            stringResource(R.string.developer_card_server_url),
            serverUrl.ifBlank { unknown },
        )
        DeveloperRow(
            stringResource(R.string.developer_card_server_version),
            serverMeta?.version ?: unknown,
        )
        DeveloperRow(
            stringResource(R.string.developer_card_server_build),
            serverMeta?.buildId ?: unknown,
        )
        DeveloperRow(
            stringResource(R.string.developer_card_api_v2),
            when (apiState?.generation) {
                null -> unknown
                ApiGeneration.V2 -> stringResource(R.string.developer_card_yes)
                ApiGeneration.V1 -> stringResource(R.string.developer_card_no)
            },
        )
        DeveloperRow(
            stringResource(R.string.developer_card_last_sync),
            lastSyncedMillis?.let {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(it))
            } ?: stringResource(R.string.settings_sync_never),
        )
        DeveloperRow(stringResource(R.string.developer_card_pending), pendingWrites.toString())
        DeveloperRow(
            stringResource(R.string.developer_card_token),
            apiState?.currentTokenId?.let {
                stringResource(R.string.developer_card_token_this_device, it)
            } ?: unknown,
        )
        DeveloperRow(
            stringResource(R.string.developer_card_app_version),
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        DeveloperRow(
            stringResource(R.string.developer_card_revision),
            revision?.full ?: stringResource(R.string.about_settings_build_unknown),
        )
        // Play builds update through the store; Obtainium only makes sense for sideloaded APKs.
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            ObtainiumRow(
                stringResource(R.string.developer_obtainium_stable),
                stringResource(R.string.developer_obtainium_stable_description),
            ) {
                onAddToObtainium(false)
            }
            ObtainiumRow(
                stringResource(R.string.developer_obtainium_rolling),
                stringResource(R.string.developer_obtainium_rolling_description),
            ) {
                onAddToObtainium(true)
            }
        }
        if (onTestUpdateNotification != null) {
            ObtainiumRow(
                stringResource(R.string.developer_test_update_notification),
                stringResource(R.string.developer_test_update_notification_description),
                onTestUpdateNotification,
            )
        }
        if (onReinstallViaShizuku != null) {
            ObtainiumRow(
                stringResource(R.string.developer_shizuku_reinstall),
                stringResource(R.string.developer_shizuku_reinstall_description),
                onReinstallViaShizuku,
            )
        }
    }
}

@Composable
private fun ObtainiumRow(title: String, description: String, onClick: () -> Unit) {
    SettingsListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Icon(
                PhosphorIcons.ArrowSquareOut,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun DeveloperRow(label: String, value: String) {
    SettingsListItem(
        headlineContent = { Text(label) },
        supportingContent = { SelectionContainer { Text(value) } },
    )
}
