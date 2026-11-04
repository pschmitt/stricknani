package blue.anika.wolle.data.update

import android.os.Build
import blue.anika.wolle.BuildConfig

/**
 * Checks GitHub Releases for this very build. The one place that feeds [BuildConfig] and the device
 * ABIs to [UpdateChecker], shared by the Home update card and the About screen.
 */
suspend fun UpdateChecker.checkCurrentBuild(channel: UpdateChannel): UpdateCheckResult =
    checkDetailed(
        gitRevision = BuildConfig.GIT_REVISION,
        versionName = BuildConfig.VERSION_NAME,
        isDebugBuild = BuildConfig.DEBUG,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        channel = channel,
    )
