package blue.anika.wolle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.settings.ThemeMode
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingDeepLink = DeepLinkParser.parse(intent?.dataString)

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
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = DeepLinkParser.parse(intent.dataString)
    }
}
