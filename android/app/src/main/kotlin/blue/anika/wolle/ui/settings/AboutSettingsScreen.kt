package blue.anika.wolle.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.R
import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.PrivilegedState
import blue.anika.wolle.data.update.UpdateChannel
import blue.anika.wolle.data.update.UpdateInstallState
import blue.anika.wolle.data.update.backgroundUpdatesAvailable
import blue.anika.wolle.data.update.canInstallInApp
import blue.anika.wolle.data.update.updateChannelChoiceVisible
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.SegmentedControl
import blue.anika.wolle.ui.common.SegmentedOption
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
    var obtainiumChoice by remember { mutableStateOf(false) }
    fun addToObtainium(channel: UpdateChannel) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(ObtainiumLink.build(channel)))
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
    if (obtainiumChoice) {
        ObtainiumChannelDialog(
            initial = updateChannel,
            onDismiss = { obtainiumChoice = false },
            onConfirm = {
                obtainiumChoice = false
                addToObtainium(it)
            },
        )
    }
    val refreshing by viewModel.aboutRefreshing.collectAsStateWithLifecycle()
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
    val serverVersion = serverMeta?.version?.takeIf { it.isNotBlank() } ?: unavailable
    // The server's commit, linked like the app's build; the random build id means nothing here.
    val serverRevision = BuildRevision.parse(serverMeta?.revision)
    val serverBuild = serverRevision?.display ?: unavailable
    // The version code only orders Play uploads; it lives on the developer card.
    val appVersion =
        appVersionLabel(BuildConfig.VERSION_NAME, BuildConfig.RELEASE_CHANNEL, BuildConfig.DEBUG)
    val buildDate = formatBuildDate(BuildConfig.BUILD_DATE)
    val revision = BuildRevision.parse(BuildConfig.GIT_REVISION)
    val build = revision?.display
    val channelChoiceVisible =
        updateChannelChoiceVisible(
            BuildConfig.SELF_UPDATE_ENABLED,
            BuildConfig.DEBUG,
            installDeveloperMode,
        )
    val shizukuUi =
        shizukuStatusUi(
            developerMode = installDeveloperMode,
            supported = installViewModel.shizukuSupported,
            state = shizukuState,
        )
    val offer = aboutUpdateOffer(updateState, installState)
    val trailing = aboutUpdateTrailing(updateState, installState)
    val availableUpdate = (updateState as? AboutUpdateState.Available)?.update
    // An install in flight, or one that just failed, belongs to the offered update.
    val installing = availableUpdate != null && installState.isBusy
    val failedInstall =
        (installState as? UpdateInstallState.Failed)?.takeIf { availableUpdate != null }
    val copyText = buildString {
        append("Stricknani $appVersion")
        if (build != null) append("\nBuild: $build")
        if (buildDate != null) append("\nBuild date: $buildDate")
        serverMeta?.let {
            append("\nServer: ${it.version}")
            serverRevision?.let { revision -> append(" (${revision.display})") }
        }
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
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refreshAbout,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { AboutHero(mascotCue) }
                item {
                    SettingsGroupCard(
                        title = stringResource(R.string.about_settings_version_title),
                        icon = PhosphorIcons.Info,
                        headerAction = {
                            IconButton(
                                onClick = { copyToClipboard(context, copyText, copiedMessage) }
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.about_copy_button),
                                )
                            }
                        },
                    ) {
                        SettingsListItem(
                            headlineContent = {
                                Text(stringResource(R.string.about_settings_app_version_label))
                            },
                            supportingContent = {
                                Column {
                                    Text(appVersion)
                                    if (installing) {
                                        updateInstallStatusText(installState)?.let { Text(it) }
                                        UpdateInstallProgress(
                                            installState,
                                            Modifier.padding(top = 8.dp),
                                        )
                                    }
                                    if (failedInstall != null) {
                                        updateInstallStatusText(installState)?.let { Text(it) }
                                        UpdateFailureActions(
                                            failedInstall.failure,
                                            onTryAgain = {
                                                installViewModel.dismissFailure()
                                                availableUpdate?.let(installViewModel::start)
                                            },
                                            onOpenBrowser = {
                                                installViewModel.dismissFailure()
                                                availableUpdate?.let {
                                                    uriHandler.openUri(it.downloadUrl)
                                                }
                                            },
                                        )
                                    }
                                    if (offer != null && !offer.canInstallInApp()) {
                                        Text(stringResource(R.string.about_update_browser_hint))
                                    }
                                }
                            },
                            trailingContent =
                                if (trailing == AboutUpdateTrailing.None) null
                                else {
                                    {
                                        AboutUpdateTrailingContent(
                                            trailing = trailing,
                                            offer = offer,
                                            channel = updateChannel,
                                            onUpdate = {
                                                // In-app install where the build supports it, else
                                                // the
                                                // browser download.
                                                offer?.let {
                                                    if (it.canInstallInApp())
                                                        installViewModel.start(it)
                                                    else uriHandler.openUri(it.downloadUrl)
                                                }
                                            },
                                            onOpenPlay = { uriHandler.openUri(PLAY_URL) },
                                        )
                                    }
                                },
                        )
                        if (backgroundUpdatesAvailable) {
                            AutoUpdateRow(
                                checked = autoUpdate,
                                silentPossible = silentPossible,
                                onChange = installViewModel::setAutoUpdate,
                            )
                        }
                        if (backgroundUpdatesAvailable) {
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
                        SettingsListItem(
                            headlineContent = {
                                Text(stringResource(R.string.about_settings_build_date_label))
                            },
                            supportingContent = {
                                Text(
                                    buildDate
                                        ?: stringResource(R.string.about_settings_build_unknown)
                                )
                            },
                        )
                        if (serverMeta != null)
                            SettingsListItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.about_settings_server_version_label)
                                    )
                                },
                                supportingContent = { Text(serverVersion) },
                            )
                        if (serverMeta != null)
                            SettingsListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.about_settings_server_build_label))
                                },
                                supportingContent = { Text(serverBuild) },
                                trailingContent =
                                    serverRevision?.let {
                                        {
                                            IconButton(
                                                onClick = { uriHandler.openUri(it.commitUrl) }
                                            ) {
                                                Icon(
                                                    PhosphorIcons.GithubLogo,
                                                    contentDescription =
                                                        stringResource(
                                                            R.string.about_open_server_commit
                                                        ),
                                                )
                                            }
                                        }
                                    },
                            )
                    }
                }
                item {
                    val motion = !animationsDisabled()
                    AnimatedVisibility(
                        // Debug builds keep the update channel choice even with developer mode
                        // off, so the card then shows only those rows.
                        visible = developerMode || channelChoiceVisible,
                        enter = if (motion) fadeIn() + expandVertically() else fadeIn(snap()),
                        exit = if (motion) fadeOut() + shrinkVertically() else fadeOut(snap()),
                    ) {
                        DeveloperCard(
                            full = developerMode,
                            updateRows = {
                                // Debug builds choose a channel too: tagged and rolling releases
                                // both carry debug APKs. Release builds need developer mode
                                // (Latest is unfinished).
                                if (channelChoiceVisible) {
                                    UpdateChannelRow(
                                        selected = updateChannel,
                                        onSelect = viewModel::setUpdateChannel,
                                    )
                                }
                            },
                            shizukuRow =
                                if (shizukuUi.visible) {
                                    {
                                        ShizukuUpdaterRow(
                                            ui = shizukuUi,
                                            enabled = shizukuEnabled,
                                            onEnabledChange = installViewModel::setShizukuEnabled,
                                            onAllow = installViewModel::requestShizukuPermission,
                                            onOpenShizuku = {
                                                openShizuku(context, installViewModel)
                                            },
                                        )
                                    }
                                } else null,
                            serverUrl = serverUrl,
                            apiState = apiState,
                            lastSyncedMillis = lastSyncedMillis,
                            pendingWrites = pendingWrites,
                            onDeveloperModeChange = viewModel::setDeveloperMode,
                            onAddToObtainium = { obtainiumChoice = true },
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
                            description =
                                stringResource(R.string.about_settings_privacy_description),
                            chevron = true,
                            onClick = {
                                uriHandler.openUri(
                                    privacyPolicyUrl(if (viewModel.isLocalMode) "" else serverUrl)
                                )
                            },
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
                            onClick = {
                                uriHandler.openUri("https://github.com/googlefonts/nunito")
                            },
                        )
                        SettingsListItem(
                            modifier =
                                Modifier.clickable(
                                    onClickLabel = stringResource(R.string.libraries_title),
                                    role = Role.Button,
                                    onClick = onShowLibraries,
                                ),
                            leadingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.LibraryBooks,
                                    contentDescription = null,
                                )
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
}

/** Compact hero: mascot (72dp, gentle float unless animations are off) beside name and tagline. */
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.graphicsLayer { if (!still) translationY = bob * density }) {
            Mascot(size = 72.dp, cue = cue)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "Stable X available" or "Latest X available", following the channel the update came from. */
@Composable
private fun availableText(update: AppUpdate, channel: UpdateChannel): String =
    stringResource(
        when (channel) {
            UpdateChannel.STABLE -> R.string.about_update_available_stable
            UpdateChannel.LATEST -> R.string.about_update_available_latest
        },
        update.version,
    )

/** The version row's trailing slot: a spinner, a status icon, the Update button or Play. */
@Composable
private fun AboutUpdateTrailingContent(
    trailing: AboutUpdateTrailing,
    offer: AppUpdate?,
    channel: UpdateChannel,
    onUpdate: () -> Unit,
    onOpenPlay: () -> Unit,
) {
    val statusTag = Modifier.testTag("about-update-status")
    when (trailing) {
        AboutUpdateTrailing.None -> Unit
        AboutUpdateTrailing.Checking -> {
            val checking = stringResource(R.string.about_update_checking)
            TrailingIconSlot {
                CircularProgressIndicator(
                    statusTag.size(24.dp).semantics { contentDescription = checking },
                    strokeWidth = 2.dp,
                )
            }
        }
        AboutUpdateTrailing.UpToDate ->
            TrailingIconSlot {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription =
                        stringResource(
                            R.string.about_update_up_to_date,
                            stringResource(channel.labelRes()),
                        ),
                    modifier = statusTag,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        AboutUpdateTrailing.AheadOfStable ->
            TrailingIconSlot {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.about_update_ahead_of_stable),
                    modifier = statusTag,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        AboutUpdateTrailing.Offer ->
            Button(onClick = onUpdate, modifier = Modifier.testTag("about-update-button")) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    // Keeps the target version audible to screen readers.
                    contentDescription = offer?.let { availableText(it, channel) },
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.about_update_button))
            }
        AboutUpdateTrailing.Failed ->
            TrailingIconSlot {
                Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = stringResource(R.string.about_update_failed),
                    modifier = statusTag,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        AboutUpdateTrailing.Play ->
            IconButton(onClick = onOpenPlay) {
                Icon(
                    PhosphorIcons.ArrowSquareOut,
                    contentDescription = stringResource(R.string.about_update_open_play),
                )
            }
    }
}

/**
 * The 48dp square an IconButton occupies, with its 24dp icon centred. Plain status icons sit in the
 * same square so they end in the same column as the icon buttons of the other rows.
 */
@Composable
private fun TrailingIconSlot(content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { content() }
}

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
                SegmentedControl(
                    options =
                        UpdateChannel.entries.map {
                            SegmentedOption(
                                it,
                                stringResource(it.labelRes()),
                                testTag = "about-update-channel-${it.name}",
                            )
                        },
                    selected = selected,
                    onSelect = onSelect,
                    label = stringResource(R.string.about_update_channel_title),
                    fullWidth = true,
                )
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
 * "Shizuku updater": a switch for installing updates through Shizuku (on by default). Off, updates
 * use the system installer even when Shizuku is ready. The status line and the setup step stay
 * visible either way, so Shizuku can be made ready before switching it on.
 */
@Composable
private fun ShizukuUpdaterRow(
    ui: ShizukuStatusUi,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAllow: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    SettingsListItem(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("about-shizuku-row")
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange),
        headlineContent = { Text(stringResource(R.string.about_shizuku_title)) },
        supportingContent = {
            Column {
                if (!enabled) {
                    Text(
                        stringResource(R.string.about_shizuku_state_off),
                        modifier = Modifier.testTag("about-shizuku-off"),
                    )
                }
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
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
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
 * Debugging facts, revealed by the Build row easter egg. The switch turns developer mode off again;
 * the action rows (Obtainium, Shizuku reinstall, test notification) do what they say, every other
 * row is read-only.
 */
@Composable
private fun DeveloperCard(
    full: Boolean,
    updateRows: @Composable ColumnScope.() -> Unit,
    shizukuRow: (@Composable ColumnScope.() -> Unit)?,
    serverUrl: String,
    apiState: blue.anika.wolle.data.api.ApiCapabilitiesState?,
    lastSyncedMillis: Long?,
    pendingWrites: Int,
    onDeveloperModeChange: (Boolean) -> Unit,
    onAddToObtainium: () -> Unit,
    onReinstallViaShizuku: (() -> Unit)?,
    onTestUpdateNotification: (() -> Unit)?,
) {
    val unknown = stringResource(R.string.developer_card_unknown)
    SettingsGroupCard(
        title = stringResource(R.string.developer_card_title),
        icon = Icons.Filled.Code,
    ) {
        val switchLabel = stringResource(R.string.developer_card_switch)
        if (full) {
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
        }
        updateRows()
        if (!full) {
            shizukuRow?.invoke(this)
            return@SettingsGroupCard
        }
        DeveloperRow(
            stringResource(R.string.developer_card_server_url),
            serverUrl.ifBlank { unknown },
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
        // Play builds update through the store; Obtainium only makes sense for sideloaded APKs.
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            ObtainiumRow(
                stringResource(R.string.developer_obtainium_title),
                stringResource(R.string.developer_obtainium_description),
                onAddToObtainium,
            )
        }
        if (onTestUpdateNotification != null) {
            ObtainiumRow(
                stringResource(R.string.developer_test_update_notification),
                stringResource(R.string.developer_test_update_notification_description),
                onTestUpdateNotification,
            )
        }
        shizukuRow?.invoke(this)
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

/** Asks which update channel Obtainium should follow, then opens its add-app link. */
@Composable
private fun ObtainiumChannelDialog(
    initial: UpdateChannel,
    onDismiss: () -> Unit,
    onConfirm: (UpdateChannel) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("about-obtainium-dialog"),
        title = { Text(stringResource(R.string.developer_obtainium_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                UpdateChannel.entries.forEach { channel ->
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = channel == selected,
                                role = Role.RadioButton,
                                onClick = { selected = channel },
                            )
                            .testTag("about-obtainium-${channel.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = channel == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                stringResource(channel.labelRes()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(channel.helpRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.developer_obtainium_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun UpdateChannel.helpRes(): Int =
    when (this) {
        UpdateChannel.STABLE -> R.string.about_update_channel_stable_help
        UpdateChannel.LATEST -> R.string.about_update_channel_latest_help
    }
