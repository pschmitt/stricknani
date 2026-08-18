package blue.anika.wolle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import blue.anika.wolle.ui.navigation.WolleNavHost
import blue.anika.wolle.ui.theme.WolleTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. Onboarding-gated start destination (server URL + PAT not yet configured ->
 * Route.Onboarding) lands in SNA-6 - this always starts at
 * [blue.anika.wolle.ui.navigation.Route.Home] for now (SNA-5: repo scaffold + Compose shell).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent { WolleTheme { WolleNavHost() } }
    }
}
