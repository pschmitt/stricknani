package blue.anika.wolle.data.update

import blue.anika.wolle.data.settings.AppPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Decides per update whether it goes through Shizuku or the regular PackageInstaller session. */
@Singleton
class InstallPathResolver
@Inject
constructor(
    val privileged: PrivilegedInstaller,
    private val preferences: AppPreferencesRepository,
    private val inspector: ApkInspector,
) {

    /** The path an update started now would take. */
    suspend fun path(): InstallPath =
        chooseInstallPath(
            preferences.shizukuInstall.first(),
            privileged.state.value,
            preferences.developerMode.first(),
        )

    /** Installs need no confirmation and no "unknown apps" setup: Shizuku is on and ready. */
    suspend fun privilegedSelected(): Boolean = path() == InstallPath.PRIVILEGED

    /** An update could install without the user: through Shizuku, or as installer of record. */
    suspend fun silentInstallPossible(): Boolean =
        privilegedSelected() || inspector.canInstallSilently()

    /** [silentInstallPossible] as a flow, for the settings UI. */
    fun silentInstallPossibleFlow(): Flow<Boolean> =
        combine(preferences.shizukuInstall, privileged.state, preferences.developerMode) {
            enabled,
            state,
            developerMode ->
            chooseInstallPath(enabled, state, developerMode) == InstallPath.PRIVILEGED ||
                inspector.canInstallSilently()
        }
}
