package blue.anika.wolle

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import blue.anika.wolle.crash.CrashReportStore
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.importAvailability
import blue.anika.wolle.data.export.DataExportNotifier
import blue.anika.wolle.data.export.ExportFormat
import blue.anika.wolle.data.imports.AppVisibility
import blue.anika.wolle.data.imports.ImportKind
import blue.anika.wolle.data.imports.ImportNotifier
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcFailure
import blue.anika.wolle.data.onboarding.QrConfigCodec
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.data.update.UpdateAvailableNotifier
import blue.anika.wolle.sync.SyncNotifier
import blue.anika.wolle.sync.syncedRoute
import blue.anika.wolle.ui.common.CrashReportDialog
import blue.anika.wolle.ui.common.LocalOwnerContext
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.OwnerContext
import blue.anika.wolle.ui.common.RequestNotificationPermissionEffect
import blue.anika.wolle.ui.navigation.DeepLinkParser
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.navigation.StricknaniNavHost
import blue.anika.wolle.ui.oidc.DEFAULT_PROVIDER_NAME
import blue.anika.wolle.ui.oidc.showOidcFailure
import blue.anika.wolle.ui.settings.SettingsCategory
import blue.anika.wolle.ui.share.ShareIntake
import blue.anika.wolle.ui.share.SharedLink
import blue.anika.wolle.ui.theme.StricknaniTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Single-activity host. The start destination reactively follows [SettingsRepository.isConfigured]:
 * [Route.Onboarding] until a server URL + API token are saved, [Route.Home] once they are. Signing
 * out (Settings, SNA-6) flips it back - see `StricknaniNavHost`'s kdoc for why that's a full
 * nav-graph recreation rather than a `navController.navigate` call.
 *
 * `android:launchMode="singleTask"` (manifest) means a project/yarn link (SNA-17) tapped while the
 * app is already running arrives via [onNewIntent] rather than a fresh [onCreate].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appPreferencesRepository: AppPreferencesRepository
    @Inject lateinit var mutationFeedback: MutationFeedback
    @Inject lateinit var oidcCoordinator: OidcCoordinator
    @Inject lateinit var apiCapabilities: ApiCapabilities
    @Inject lateinit var mediaUrlResolver: MediaUrlResolver
    @Inject lateinit var appVisibility: AppVisibility

    private var pendingDeepLink by mutableStateOf<Route?>(null)
    // SNA-61: `stricknani://setup?p=...` (the manifest's own registered intent-filter, so a
    // generic camera app's QR auto-detection can hand this back to us too, not just the in-app
    // scanner) used to just fall through DeepLinkParser (which only recognizes /projects/{id} and
    // /yarn/{id} paths) and get silently discarded. Route it into the same
    // QrConfigCodec.decode -> OnboardingViewModel.connectFromScannedText path the in-app scanner
    // already uses instead - see `StricknaniNavHost`/`OnboardingScreen`.
    private var pendingSetupUri by mutableStateOf<String?>(null)
    private var pendingCrashReport by mutableStateOf<String?>(null)
    // A link shared to the app (ACTION_SEND text/plain). Kept here, not in the nav graph, so it
    // survives onboarding (which recreates the graph), rotation and process death
    // (onSaveInstanceState). The chooser asks before anything is imported.
    private var pendingSharedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingDeepLink = routeFor(intent)
        pendingSetupUri = intent?.dataString?.takeIf(QrConfigCodec::looksLikeQrConfigUri)
        pendingCrashReport = CrashReportStore(applicationContext).takePending()
        // A recreated activity (rotation, process death) still holds the original share intent:
        // restore the saved pending link instead of reading the intent again, or a link the user
        // already answered would come back. Relaunching from Recents replays it too.
        if (savedInstanceState != null) {
            pendingSharedUrl = savedInstanceState.getString(STATE_PENDING_SHARE)
        } else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            handleShare(intent)
        }
        // A recreated activity (rotation) still holds the original intent: only a fresh launch may
        // process an OIDC callback, a one-time link that must not run twice.
        if (savedInstanceState == null) handleOidcCallback(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                oidcCoordinator.browserRequests.collect(::openAuthorizationPage)
            }
        }

        setContent {
            val isConfigured by settingsRepository.isConfigured.collectAsStateWithLifecycle()
            val themeMode by
                appPreferencesRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            val dynamicColor by
                appPreferencesRepository.dynamicColor.collectAsStateWithLifecycle(false)
            // Status and navigation bar icons follow the app's own theme, not the system one:
            // with "Light" chosen in the app on a phone in dark mode they stayed white on the
            // light app (and dark on dark the other way round).
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle =
                        SystemBarStyle.auto(
                            AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT,
                        ) {
                            darkTheme
                        },
                    navigationBarStyle =
                        SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            val apiState by apiCapabilities.state.collectAsStateWithLifecycle()
            val ownerContext =
                remember(apiState.user?.id) {
                    OwnerContext(
                        currentUserId = apiState.user?.id,
                        resolveUrl = mediaUrlResolver::resolve,
                    )
                }
            StricknaniTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                CompositionLocalProvider(LocalOwnerContext provides ownerContext) {
                    StricknaniNavHost(
                        startDestination = if (isConfigured) Route.Home else Route.Onboarding,
                        // Deferred (not consumed) until onboarding is done - there's nothing to
                        // navigate to yet, and StricknaniNavHost gets a fresh NavController once
                        // isConfigured flips anyway (see its kdoc).
                        pendingDeepLinkRoute = pendingDeepLink.takeIf { isConfigured },
                        onDeepLinkConsumed = { pendingDeepLink = null },
                        // Only meaningful pre-onboarding - once configured, a stray setup URI (an
                        // old
                        // QR scanned again, say) has nothing useful to do.
                        pendingSetupUri = pendingSetupUri.takeIf { !isConfigured },
                        onSetupUriConsumed = { pendingSetupUri = null },
                        pendingSharedUrl = pendingSharedUrl,
                        onSharedUrlConsumed = { pendingSharedUrl = null },
                        importAvailability = apiState.importAvailability(),
                        mutationFeedback = mutationFeedback,
                    )
                }
                if (isConfigured) {
                    RequestNotificationPermissionEffect(appPreferencesRepository)
                }
                pendingCrashReport?.let { report ->
                    CrashReportDialog(
                        report = report,
                        onCopy = { copyCrashReport(report) },
                        onRestart = ::restartApplication,
                        onDismiss = { pendingCrashReport = null },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appVisibility.started()
    }

    override fun onStop() {
        appVisibility.stopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = routeFor(intent)
        pendingSetupUri = intent.dataString?.takeIf(QrConfigCodec::looksLikeQrConfigUri)
        handleOidcCallback(intent)
        handleShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSharedUrl?.let { outState.putString(STATE_PENDING_SHARE, it) }
    }

    /**
     * A share of plain text: keep its first http(s) link for the chooser, or say there is none. The
     * share is dropped from the intent so nothing can handle it twice.
     */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        when (
            val intake =
                SharedLink.intake(
                    intent.action,
                    intent.type,
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                )
        ) {
            ShareIntake.NotAShare -> return
            ShareIntake.NoLink ->
                Toast.makeText(this, getString(R.string.share_no_link), Toast.LENGTH_SHORT).show()
            is ShareIntake.Link ->
                if (settingsRepository.isLocalMode) {
                    // Link imports need the network and a server; this phone has neither role.
                    Toast.makeText(
                            this,
                            getString(R.string.share_local_unavailable),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                } else {
                    pendingSharedUrl = intake.url
                }
        }
        intent.action = null
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    /**
     * A web link, or a notification: "update available" opens Settings -> About, "synced from
     * another device" opens the list that changed.
     */
    private fun routeFor(intent: Intent?): Route? =
        when (intent?.action) {
            UpdateAvailableNotifier.ACTION_OPEN_UPDATE ->
                Route.SettingsCategoryRoute(SettingsCategory.About.name)
            SyncNotifier.ACTION_OPEN_SYNCED ->
                syncedRoute(
                    intent.getStringExtra(SyncNotifier.EXTRA_SYNCED),
                    projectId =
                        intent.getIntExtra(SyncNotifier.EXTRA_PROJECT_ID, NO_ID).takeIf {
                            it != NO_ID
                        },
                    yarnId =
                        intent.getIntExtra(SyncNotifier.EXTRA_YARN_ID, NO_ID).takeIf {
                            it != NO_ID
                        },
                )
            // A background import is done: the import dialog on its result.
            ImportNotifier.ACTION_OPEN_IMPORT ->
                intent.getStringExtra(ImportNotifier.EXTRA_IMPORT_URL)?.let { url ->
                    Route.CreateImport(
                        kind =
                            intent.getStringExtra(ImportNotifier.EXTRA_IMPORT_KIND)
                                ?: ImportKind.Project.name,
                        url = url,
                        autoStart = true,
                    )
                }
            DataExportNotifier.ACTION_OPEN_BACKUPS -> backupsRoute()
            DataExportNotifier.ACTION_OPEN_EXPORT -> openExportedFile(intent) ?: backupsRoute()
            else -> DeepLinkParser.parse(intent?.dataString)
        }

    private fun backupsRoute(): Route = Route.SettingsCategoryRoute(SettingsCategory.Backup.name)

    /**
     * "Your data is saved" opens the archive in whatever app handles it. Null when that worked, the
     * Backups route when nothing can open it. The action is dropped so a rotation does not open it
     * a second time.
     */
    private fun openExportedFile(intent: Intent): Route? {
        intent.action = null
        val uri = intent.getStringExtra(DataExportNotifier.EXTRA_EXPORT_URI)?.toUri()
        intent.removeExtra(DataExportNotifier.EXTRA_EXPORT_URI)
        if (uri == null) return backupsRoute()
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    uri,
                    intent.getStringExtra(DataExportNotifier.EXTRA_EXPORT_MIME)
                        ?: ExportFormat.TarGz.mimeType,
                )
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            startActivity(view)
            null
        } catch (_: ActivityNotFoundException) {
            backupsRoute()
        } catch (_: SecurityException) {
            backupsRoute()
        }
    }

    /**
     * `stricknani://oidc-callback?...` is the return from the browser sign-in. It is handled once
     * and then dropped from the intent; one that matches no pending in-app flow (stale, replayed or
     * injected) is ignored with a friendly note.
     */
    private fun handleOidcCallback(intent: Intent?) {
        val data = intent?.dataString ?: return
        when (oidcCoordinator.onCallback(data)) {
            OidcCoordinator.CallbackResult.NotACallback -> return
            OidcCoordinator.CallbackResult.Rejected ->
                mutationFeedback.showOidcFailure(OidcFailure.Expired, DEFAULT_PROVIDER_NAME)
            OidcCoordinator.CallbackResult.Accepted -> Unit
        }
        intent.data = null
    }

    /** Opens the provider's authorization page in a Custom Tab (RFC 8252: not a WebView). */
    private fun openAuthorizationPage(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") {
            mutationFeedback.showOidcFailure(OidcFailure.Other(null), DEFAULT_PROVIDER_NAME)
            return
        }
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            mutationFeedback.showOidcFailure(OidcFailure.Other(null), DEFAULT_PROVIDER_NAME)
        }
    }

    private fun copyCrashReport(report: String) {
        getSystemService<ClipboardManager>()
            ?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.main_activity_crash_report_label), report)
            )
        Toast.makeText(
                this,
                getString(R.string.main_activity_crash_report_copied),
                Toast.LENGTH_SHORT,
            )
            .show()
    }

    private fun restartApplication() {
        pendingCrashReport = null
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launchIntent)
        }
        finishAffinity()
    }
}

private const val NO_ID = -1
private const val STATE_PENDING_SHARE = "pending_shared_url"

/** The same translucent navigation-bar scrims androidx uses by default for button navigation. */
private val LIGHT_NAV_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_NAV_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
