package blue.anika.wolle.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.Mascot
import blue.anika.wolle.ui.common.PhosphorIcons
import blue.anika.wolle.ui.common.animationsDisabled
import blue.anika.wolle.ui.common.privacyPolicyUrl

private const val GITHUB_URL = "https://github.com/pschmitt/stricknani"
private const val SPONSOR_URL = "https://github.com/sponsors/pschmitt"
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

    LaunchedEffect(Unit) {
        viewModel.developerModeToast.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val unavailable = stringResource(R.string.about_settings_server_version_unavailable)
    val serverVersion = serverMeta?.let { "${it.version} (${it.buildId})" } ?: unavailable
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val copyText =
        stringResource(
            R.string.about_copy_template,
            appVersion,
            BuildConfig.GIT_REVISION,
            serverVersion,
        )
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
            item { AboutHero() }
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_settings_version_title),
                    icon = PhosphorIcons.Info,
                ) {
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_app_version_label))
                        },
                        supportingContent = { Text(appVersion) },
                    )
                    SettingsListItem(
                        modifier = Modifier.clickable(onClick = viewModel::onBuildRowTap),
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_build_label))
                        },
                        supportingContent = { Text(BuildConfig.GIT_REVISION) },
                    )
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
                    SettingsListItem(
                        headlineContent = {
                            Text(stringResource(R.string.about_settings_license_label))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.about_credits_license_value))
                        },
                    )
                    SettingsListItem(
                        headlineContent = { Text(stringResource(R.string.about_credits_icons)) },
                        supportingContent = {
                            Text(stringResource(R.string.about_credits_icons_value))
                        },
                    )
                    SettingsListItem(
                        headlineContent = { Text(stringResource(R.string.about_credits_fonts)) },
                        supportingContent = {
                            Text(stringResource(R.string.about_credits_fonts_value))
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
private fun AboutHero() {
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
            Mascot(size = 120.dp)
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

/** A link row: icon chip, label and a short description; opens in the browser. */
@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    warm: Boolean = false,
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
                PhosphorIcons.ArrowSquareOut,
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
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}
