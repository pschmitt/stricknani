package blue.anika.wolle.ui.common

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import blue.anika.wolle.R
import blue.anika.wolle.data.settings.AppPreferencesRepository
import blue.anika.wolle.data.settings.DEVICE_UNLOCK_DEFAULT
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Fingerprint, face or screen lock (PIN, pattern, password). Fingerprint or face alone is not
 * enough on API 28-29, where `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is not supported: there the
 * weak class stands in (still fingerprint, face or the screen lock). Below API 28 the androidx
 * compat layer hosts the credential fallback itself.
 */
internal fun deviceUnlockAuthenticators(sdkInt: Int): Int =
    if (sdkInt >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/** What `BiometricManager.canAuthenticate` says about using the prompt. */
internal fun lockStateFor(canAuthenticate: Int): DeviceLockState =
    when (canAuthenticate) {
        // No screen lock and no biometrics enrolled (or nothing to ask): nothing to unlock with.
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> DeviceLockState.NotSecured
        // Success, or a temporary problem the prompt reports itself.
        else -> DeviceLockState.Available
    }

/** The androidx [BiometricPrompt] as a [DeviceAuthenticator]. Needs a started activity. */
internal class BiometricDeviceAuthenticator(
    private val activity: FragmentActivity,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : DeviceAuthenticator {
    override fun lockState(): DeviceLockState = deviceLockState(activity, sdkInt)

    override fun authenticate(title: String, subtitle: String, onResult: (UnlockResult) -> Unit) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onResult(UnlockResult.Failed(null))
            return
        }
        val prompt =
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) = onResult(UnlockResult.Success)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onResult(
                            when (errorCode) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED -> UnlockResult.Cancelled
                                else -> UnlockResult.Failed(errString.toString())
                            }
                        )
                    }
                },
            )
        // No negative button: with DEVICE_CREDENTIAL the system supplies the way out.
        val info =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(deviceUnlockAuthenticators(sdkInt))
                .build()
        prompt.authenticate(info)
    }
}

internal fun deviceLockState(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
): DeviceLockState =
    lockStateFor(BiometricManager.from(context).canAuthenticate(deviceUnlockAuthenticators(sdkInt)))

/**
 * The app's [DangerousActionGuard]. `MainActivity` attaches itself while it exists (the prompt
 * needs an activity); the setting is read from [AppPreferencesRepository] (default on).
 */
@Singleton
class DeviceUnlock
@Inject
constructor(
    @ApplicationContext private val context: Context,
    preferences: AppPreferencesRepository,
) : DangerousActionGuard {
    @Volatile private var enabled = DEVICE_UNLOCK_DEFAULT
    private var activity: WeakReference<FragmentActivity>? = null

    private val gate =
        DangerousActionGate(
            isEnabled = { enabled },
            authenticator = {
                activity?.get()?.takeUnless { it.isFinishing }?.let { BiometricDeviceAuthenticator(it) }
            },
        )

    init {
        preferences.deviceUnlockEnabled
            .onEach { enabled = it }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    fun attach(host: FragmentActivity) {
        activity = WeakReference(host)
    }

    fun detach(host: FragmentActivity) {
        if (activity?.get() === host) activity = null
    }

    /** Whether the device has a screen lock or biometrics, for the setting's hint. */
    fun lockState(): DeviceLockState = deviceLockState(context)

    override fun confirm(@StringRes reason: Int, action: () -> Unit) = run(reason, false, action)

    override fun confirmAlways(@StringRes reason: Int, action: () -> Unit) =
        run(reason, true, action)

    private fun run(@StringRes reason: Int, enforce: Boolean, action: () -> Unit) {
        gate.confirm(
            title = context.getString(R.string.unlock_title, context.getString(reason)),
            subtitle = context.getString(R.string.unlock_subtitle),
            enforce = enforce,
            onFailure = { message ->
                Toast.makeText(
                        context,
                        message ?: context.getString(R.string.unlock_failed),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            },
            action = action,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DangerousActionGuardModule {
    @Binds abstract fun bindGuard(impl: DeviceUnlock): DangerousActionGuard
}
