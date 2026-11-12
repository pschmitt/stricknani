package blue.anika.wolle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.DEVICE_UNLOCK_DEFAULT
import blue.anika.wolle.ui.common.DangerousActionGuard
import blue.anika.wolle.ui.common.DeviceLockState
import blue.anika.wolle.ui.common.DeviceUnlock
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the "Unlock to confirm dangerous actions" row needs; the setting is per device. */
interface DeviceLockProbe {
    fun lockState(): DeviceLockState
}

/**
 * The per-device "Unlock to confirm dangerous actions" setting. Turning it off asks for the unlock
 * itself (even though the setting is on, which is why it uses `confirmAlways`).
 */
@HiltViewModel
class DeviceUnlockSettingViewModel
internal constructor(
    private val preferences: AppPreferencesRepository,
    private val guard: DangerousActionGuard,
    private val lockProbe: DeviceLockProbe,
) : ViewModel() {
    @Inject
    constructor(
        preferences: AppPreferencesRepository,
        unlock: DeviceUnlock,
    ) : this(preferences, unlock, object : DeviceLockProbe {
        override fun lockState() = unlock.lockState()
    })

    val enabled: StateFlow<Boolean> =
        preferences.deviceUnlockEnabled.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DEVICE_UNLOCK_DEFAULT,
        )

    /** The hint "Set up a screen lock to use this" shows while the device has none. */
    fun needsScreenLock(): Boolean = lockProbe.lockState() == DeviceLockState.NotSecured

    fun setEnabled(value: Boolean) {
        if (value) {
            viewModelScope.launch { preferences.setDeviceUnlockEnabled(true) }
        } else {
            guard.confirmAlways(R.string.unlock_reason_turn_off) {
                viewModelScope.launch { preferences.setDeviceUnlockEnabled(false) }
            }
        }
    }
}
