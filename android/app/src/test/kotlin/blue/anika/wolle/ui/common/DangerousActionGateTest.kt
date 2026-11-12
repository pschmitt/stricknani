package blue.anika.wolle.ui.common

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

class DangerousActionGateTest {
    private class FakeAuthenticator(var lock: DeviceLockState = DeviceLockState.Available) :
        DeviceAuthenticator {
        var prompts = 0
        var title: String? = null
        var subtitle: String? = null
        private var pending: ((UnlockResult) -> Unit)? = null

        override fun lockState() = lock

        override fun authenticate(
            title: String,
            subtitle: String,
            onResult: (UnlockResult) -> Unit,
        ) {
            prompts++
            this.title = title
            this.subtitle = subtitle
            pending = onResult
        }

        /** The user answers the open prompt. */
        fun answer(result: UnlockResult) = checkNotNull(pending).invoke(result)
    }

    private var enabled = true
    private val auth = FakeAuthenticator()
    private var present: DeviceAuthenticator? = auth
    private val gate = DangerousActionGate({ enabled }, { present })
    private var ran = 0
    private val failures = mutableListOf<String?>()

    private fun confirm(enforce: Boolean = false) =
        gate.confirm("Confirm: delete permanently", "sub", enforce, { failures += it }) { ran++ }

    @Test
    fun `setting off runs the action without a prompt`() {
        enabled = false
        confirm()
        assertEquals(1, ran)
        assertEquals(0, auth.prompts)
    }

    @Test
    fun `setting on prompts and runs only after success`() {
        confirm()
        assertEquals(0, ran)
        assertEquals(1, auth.prompts)
        assertEquals("Confirm: delete permanently", auth.title)
        assertEquals("sub", auth.subtitle)
        auth.answer(UnlockResult.Success)
        assertEquals(1, ran)
    }

    @Test
    fun `cancel runs nothing and reports nothing`() {
        confirm()
        auth.answer(UnlockResult.Cancelled)
        assertEquals(0, ran)
        assertEquals(emptyList<String?>(), failures)
    }

    @Test
    fun `a failed prompt runs nothing and reports the system text`() {
        confirm()
        auth.answer(UnlockResult.Failed("Too many attempts"))
        assertEquals(0, ran)
        assertEquals(listOf<String?>("Too many attempts"), failures)
    }

    @Test
    fun `no secure lock screen falls back to running the action`() {
        auth.lock = DeviceLockState.NotSecured
        confirm()
        assertEquals(1, ran)
        assertEquals(0, auth.prompts)
    }

    @Test
    fun `no host for the prompt fails closed`() {
        present = null
        confirm()
        assertEquals(0, ran)
        assertEquals(listOf<String?>(null), failures)
    }

    @Test
    fun `enforce prompts even when the setting is off`() {
        enabled = false
        confirm(enforce = true)
        assertEquals(0, ran)
        assertEquals(1, auth.prompts)
        auth.answer(UnlockResult.Success)
        assertEquals(1, ran)
    }

    @Test
    fun `a second request while a prompt is open is dropped`() {
        confirm()
        confirm()
        assertEquals(1, auth.prompts)
        auth.answer(UnlockResult.Success)
        assertEquals(1, ran)
        // After the answer the gate takes requests again.
        confirm()
        assertEquals(2, auth.prompts)
    }

    @Test
    fun `account deletion does not proceed without a successful unlock`() {
        var deleted = 0
        val deleteAccount = { gate.confirm("Confirm: delete account", "sub") { deleted++ } }

        deleteAccount()
        auth.answer(UnlockResult.Cancelled)
        deleteAccount()
        auth.answer(UnlockResult.Failed(null))
        assertEquals(0, deleted)

        deleteAccount()
        auth.answer(UnlockResult.Success)
        assertEquals(1, deleted)
    }

    @Test
    fun `strong biometrics only combine with the credential from API 30`() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, deviceUnlockAuthenticators(30))
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, deviceUnlockAuthenticators(36))
        // API 28-29 (and 26-27) do not support STRONG | DEVICE_CREDENTIAL.
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, deviceUnlockAuthenticators(29))
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, deviceUnlockAuthenticators(28))
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, deviceUnlockAuthenticators(26))
    }

    @Test
    fun `lock state follows BiometricManager`() {
        assertEquals(
            DeviceLockState.Available,
            lockStateFor(BiometricManager.BIOMETRIC_SUCCESS),
        )
        assertEquals(
            DeviceLockState.NotSecured,
            lockStateFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            DeviceLockState.NotSecured,
            lockStateFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            DeviceLockState.NotSecured,
            lockStateFor(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED),
        )
        // A temporary problem is reported by the prompt itself, not skipped.
        assertEquals(
            DeviceLockState.Available,
            lockStateFor(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
    }
}
