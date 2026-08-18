package blue.anika.wolle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.crash.CrashReportStore
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.ThemeMode
import blue.anika.wolle.ui.common.CrashReportDialog
import blue.anika.wolle.ui.navigation.DeepLinkParser
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.navigation.StricknaniNavHost
import blue.anika.wolle.ui.theme.StricknaniTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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

    private var pendingDeepLink by mutableStateOf<Route?>(null)
    private var pendingCrashReport by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingDeepLink = DeepLinkParser.parse(intent?.dataString)
        pendingCrashReport = CrashReportStore(applicationContext).takePending()

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
            StricknaniTheme(darkTheme = darkTheme) {
                StricknaniNavHost(
                    startDestination = if (isConfigured) Route.Home else Route.Onboarding,
                    // Deferred (not consumed) until onboarding is done - there's nothing to
                    // navigate to yet, and StricknaniNavHost gets a fresh NavController once
                    // isConfigured flips anyway (see its kdoc).
                    pendingDeepLinkRoute = pendingDeepLink.takeIf { isConfigured },
                    onDeepLinkConsumed = { pendingDeepLink = null },
                )
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = DeepLinkParser.parse(intent.dataString)
    }

    private fun copyCrashReport(report: String) {
        getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText("Crash report", report))
        Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
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
