package blue.anika.wolle.ui.update

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.InstallPathResolver
import blue.anika.wolle.data.update.PrivilegedState
import blue.anika.wolle.data.update.UpdateInstallController
import blue.anika.wolle.data.update.UpdateInstallState
import blue.anika.wolle.data.update.UpdateNotifier
import blue.anika.wolle.data.update.UpdateScheduler
import blue.anika.wolle.data.update.inAppUpdateAvailable
import blue.anika.wolle.data.update.installRunningBuild
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/** The in-app update as the Home card and the About screen see it. */
@HiltViewModel
class UpdateInstallViewModel
@Inject
constructor(
    private val controller: UpdateInstallController,
    private val scheduler: UpdateScheduler,
    private val notifier: UpdateNotifier,
    private val resolver: InstallPathResolver,
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferencesRepository,
) : ViewModel() {

    val state: StateFlow<UpdateInstallState> = controller.state

    val autoUpdate: StateFlow<Boolean> =
        preferences.autoUpdate.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun start(update: AppUpdate) {
        notifier.cancel()
        scheduler.startInstall(update)
    }

    /** Clears a failure so the plain update offer shows again. */
    fun dismissFailure() {
        controller.reset()
        notifier.cancel()
    }

    fun setAutoUpdate(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoUpdate(enabled)
            scheduler.setAutoCheck(enabled && inAppUpdateAvailable)
        }
    }

    /** Updates install without a confirmation: through Shizuku, or as installer of record. */
    val silentInstallPossible: StateFlow<Boolean> =
        resolver.silentInstallPossibleFlow().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the Shizuku integration is part of this build at all. */
    val shizukuSupported: Boolean
        get() = BuildConfig.SELF_UPDATE_ENABLED

    val shizukuState: StateFlow<PrivilegedState> = resolver.privileged.state

    val shizukuEnabled: StateFlow<Boolean> =
        preferences.shizukuInstall.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun refreshShizuku() = resolver.privileged.refresh()

    /** Switches silent installs through Shizuku on or off; on, it asks for permission if needed. */
    fun setShizukuEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setShizukuInstall(enabled)
            if (enabled) {
                resolver.privileged.refresh()
                if (resolver.privileged.state.value == PrivilegedState.PERMISSION_NEEDED) {
                    resolver.privileged.requestPermission()
                }
            }
        }
    }

    fun requestShizukuPermission() {
        resolver.privileged.refresh()
        resolver.privileged.requestPermission()
    }

    fun shizukuManagerIntent(): Intent? = resolver.privileged.managerLaunchIntent()

    /**
     * Developer action: reinstalls the running APK through Shizuku, to test the privileged path.
     */
    fun reinstallThisBuild() {
        viewModelScope.launch {
            try {
                resolver.privileged.installRunningBuild(context)
            } catch (e: Exception) {
                Timber.w(e, "Reinstall through Shizuku failed")
            }
        }
    }

    fun attachUi() = controller.attachUi()

    fun detachUi() = controller.detachUi()

    fun takeConfirmationIntent(): Intent? = controller.takeConfirmationIntent()
}
