package blue.anika.wolle.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blue.anika.wolle.data.settings.AppPreferencesRepository

internal fun shouldRequestNotificationPermission(
    sdkInt: Int,
    permissionGranted: Boolean,
    requestAlreadyMade: Boolean,
): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU && !permissionGranted && !requestAlreadyMade

/**
 * Requests `POST_NOTIFICATIONS` (Android 13+) once, the first time this composable enters
 * composition - used from the configured app shell rather than at cold start, so the prompt appears
 * once the user has actually reached the main shell (post-onboarding) instead of before they've
 * seen any value in it. A no-op on API < 33 (the permission doesn't exist) or once already granted.
 */
@Composable
fun RequestNotificationPermissionEffect(appPreferencesRepository: AppPreferencesRepository) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val requestAlreadyMade by
        appPreferencesRepository.notificationPermissionRequested.collectAsStateWithLifecycle(false)
    var requestStarted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(requestAlreadyMade) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted && !requestAlreadyMade && !requestStarted) {
            appPreferencesRepository.markNotificationPermissionRequested()
        } else if (
            shouldRequestNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                permissionGranted = granted,
                requestAlreadyMade = requestAlreadyMade || requestStarted,
            )
        ) {
            requestStarted = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            appPreferencesRepository.markNotificationPermissionRequested()
        }
    }
}
