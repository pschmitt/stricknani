package blue.anika.wolle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.navigation.StricknaniNavHost
import blue.anika.wolle.ui.theme.StricknaniTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host. The start destination reactively follows
 * [SettingsRepository.isConfigured]: [Route.Onboarding] until a server URL + API token are
 * saved, [Route.Home] once they are. Signing out (Settings, SNA-6) flips it back - see
 * `StricknaniNavHost`'s kdoc for why that's a full nav-graph recreation rather than a
 * `navController.navigate` call.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isConfigured by
                settingsRepository.isConfigured.collectAsStateWithLifecycle()
            StricknaniTheme {
                StricknaniNavHost(
                    startDestination = if (isConfigured) Route.Home else Route.Onboarding
                )
            }
        }
    }
}
