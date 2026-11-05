package blue.anika.wolle.data.update

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** Decides per update whether it goes through Shizuku or the regular PackageInstaller session. */
@Singleton
class InstallPathResolver
@Inject
constructor(val privileged: PrivilegedInstaller, private val inspector: ApkInspector) {

    /**
     * The path an update started now would take. The state is re-read first: Home has no on-resume
     * refresh and the permission may have been granted in the Shizuku app meanwhile.
     */
    suspend fun path(): InstallPath {
        privileged.refresh()
        return chooseInstallPath(privileged.state.value)
    }

    /** Installs need no confirmation and no "unknown apps" setup: Shizuku is ready. */
    suspend fun privilegedSelected(): Boolean = path() == InstallPath.PRIVILEGED

    /** An update could install without the user: through Shizuku, or as installer of record. */
    suspend fun silentInstallPossible(): Boolean =
        privilegedSelected() || inspector.canInstallSilently()

    /** [silentInstallPossible] as a flow, for the settings UI. */
    fun silentInstallPossibleFlow(): Flow<Boolean> =
        privileged.state.map {
            chooseInstallPath(it) == InstallPath.PRIVILEGED || inspector.canInstallSilently()
        }

    /**
     * Shows the Shizuku permission dialog when the service runs and the app may still ask, and
     * waits for the answer. An ignored dialog times out and the update continues on whichever path
     * applies.
     */
    suspend fun askForShizukuIfNeeded() {
        privileged.refresh()
        if (privileged.state.value != PrivilegedState.PERMISSION_NEEDED) return
        privileged.requestPermission()
        withTimeoutOrNull(PERMISSION_WAIT_MS) {
            privileged.state.first { it != PrivilegedState.PERMISSION_NEEDED }
        }
    }

    internal companion object {
        const val PERMISSION_WAIT_MS = 30_000L
    }
}
