package blue.anika.wolle.data.update

import android.content.Intent
import android.content.IntentSender
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How far along the privileged (Shizuku) install path is. */
enum class PrivilegedState {
    /** No Shizuku/Sui on the device (or this build does not carry the integration). */
    UNAVAILABLE,

    /** Shizuku is installed but its service is not running. */
    NOT_RUNNING,

    /** The service runs; this app has not been allowed to use it yet (it can still ask). */
    PERMISSION_NEEDED,

    /** The user denied the permission (or ticked "don't ask again"); only Shizuku can undo it. */
    PERMISSION_DENIED,

    /** Installs can go through the privileged binder. */
    READY,
}

/** The two ways an update APK reaches the system. */
enum class InstallPath {
    PRIVILEGED,
    PACKAGE_INSTALLER,
}

/** Maps what Shizuku reports to the state the UI and the installer route by. */
internal fun privilegedState(
    managerInstalled: Boolean,
    binderAlive: Boolean,
    permissionGranted: Boolean,
    supportedVersion: Boolean = true,
    permissionDenied: Boolean = false,
): PrivilegedState =
    when {
        binderAlive && !supportedVersion -> PrivilegedState.UNAVAILABLE
        binderAlive && permissionGranted -> PrivilegedState.READY
        binderAlive && permissionDenied -> PrivilegedState.PERMISSION_DENIED
        binderAlive -> PrivilegedState.PERMISSION_NEEDED
        managerInstalled -> PrivilegedState.NOT_RUNNING
        else -> PrivilegedState.UNAVAILABLE
    }

/** Updates go through Shizuku whenever it is ready right now, with no setting involved. */
internal fun chooseInstallPath(state: PrivilegedState): InstallPath =
    if (state == PrivilegedState.READY) InstallPath.PRIVILEGED else InstallPath.PACKAGE_INSTALLER

/**
 * Installs an APK through a privileged binder (Shizuku), so no confirmation is ever needed. The
 * implementation lives in the self-update source set only; shared code sees this interface and, in
 * builds without it, [NoPrivilegedInstaller].
 */
interface PrivilegedInstaller {
    val state: StateFlow<PrivilegedState>

    /** Re-reads the Shizuku state (binder alive, permission). */
    fun refresh()

    /** Asks Shizuku for permission; the answer arrives through [state]. */
    fun requestPermission()

    /** Opens the Shizuku app, or null when it is not installed. */
    fun managerLaunchIntent(): Intent?

    /** Commits [file] in a privileged install session; the verdict goes to [resultSender]. */
    fun install(file: File, resultSender: IntentSender)
}

/** Stands in where the build has no Shizuku integration (the Play bundle). */
object NoPrivilegedInstaller : PrivilegedInstaller {
    override val state: StateFlow<PrivilegedState> = MutableStateFlow(PrivilegedState.UNAVAILABLE)

    override fun refresh() = Unit

    override fun requestPermission() = Unit

    override fun managerLaunchIntent(): Intent? = null

    override fun install(file: File, resultSender: IntentSender) {
        error("No privileged installer in this build")
    }
}

/** Marks the real implementation the self-update source set binds, when it is in the build. */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class SelfUpdateInstaller

@Module
@InstallIn(SingletonComponent::class)
abstract class PrivilegedInstallerModule {
    @BindsOptionalOf @SelfUpdateInstaller abstract fun optionalInstaller(): PrivilegedInstaller

    companion object {
        @Provides
        @Singleton
        fun installer(
            @SelfUpdateInstaller optional: Optional<PrivilegedInstaller>
        ): PrivilegedInstaller = optional.orElse(NoPrivilegedInstaller)
    }
}
