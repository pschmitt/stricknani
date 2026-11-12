package blue.anika.wolle.data.account

import blue.anika.wolle.data.device.DeviceNameProvider
import blue.anika.wolle.data.device.deviceTokenName
import blue.anika.wolle.data.impersonation.SessionMode
import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class RenameOutcome {
    Renamed,

    /** An older server (401/404/405): nothing to do, nothing to tell the user. */
    Unsupported,
    Failed,
}

/**
 * A setup QR / deep link hands the app a token the web named "Stricknani app (setup)". Once, right
 * after that first connect, this renames it to `Android · <device name>` so the "Apps and devices"
 * list tells the phones apart. Best effort and silent: the outcome is never shown to the user.
 * Password and OIDC logins send the device name when minting, so they never come through here.
 */
@Singleton
class DeviceTokenRenamer
internal constructor(
    private val api: AccountV2Api,
    private val deviceName: () -> String,
    private val isPending: () -> Boolean,
    private val setPending: (Boolean) -> Unit,
) {
    @Inject
    constructor(
        api: AccountV2Api,
        settings: SettingsRepository,
        deviceNames: DeviceNameProvider,
    ) : this(
        api,
        { deviceNames.deviceName() },
        // The renaming belongs to the administrator's own token, not to an impersonation.
        { settings.pendingTokenRename && !SessionMode.impersonating },
        { settings.pendingTokenRename = it },
    )

    /** Marks the freshly connected token as needing the rename; call after saving it. */
    fun markPending() = setPending(true)

    /** Marks the setup token, then renames it. Returns null if nothing was pending. */
    suspend fun renameAfterSetup(): RenameOutcome? {
        markPending()
        return renameIfPending()
    }

    suspend fun renameIfPending(): RenameOutcome? {
        if (!isPending()) return null
        // One attempt per setup, whatever the answer: a failure is not worth a retry loop.
        setPending(false)
        return try {
            // Not cancelled with the onboarding screen that started it: the flag is already spent.
            withContext(NonCancellable) {
                api.renameCurrentToken(RenameTokenRequest(deviceTokenName(deviceName())))
            }
            RenameOutcome.Renamed
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val http = failure.httpFailure()
            if (http != null && http.status in UNSUPPORTED) RenameOutcome.Unsupported
            else RenameOutcome.Failed
        }
    }

    private companion object {
        val UNSUPPORTED = setOf(401, 404, 405)
    }
}
