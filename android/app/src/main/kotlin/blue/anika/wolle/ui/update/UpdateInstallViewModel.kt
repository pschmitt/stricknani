package blue.anika.wolle.ui.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.update.ApkInspector
import blue.anika.wolle.data.update.AppUpdate
import blue.anika.wolle.data.update.UpdateInstallController
import blue.anika.wolle.data.update.UpdateInstallState
import blue.anika.wolle.data.update.UpdateNotifier
import blue.anika.wolle.data.update.UpdateScheduler
import blue.anika.wolle.data.update.inAppUpdateAvailable
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The in-app update as the Home card and the About screen see it. */
@HiltViewModel
class UpdateInstallViewModel
@Inject
constructor(
    private val controller: UpdateInstallController,
    private val scheduler: UpdateScheduler,
    private val notifier: UpdateNotifier,
    private val inspector: ApkInspector,
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

    /** True once this app is its own installer of record, so updates need no confirmation. */
    fun silentInstallPossible(): Boolean = inspector.canInstallSilently()

    fun attachUi() = controller.attachUi()

    fun detachUi() = controller.detachUi()

    fun takeConfirmationIntent(): Intent? = controller.takeConfirmationIntent()
}
