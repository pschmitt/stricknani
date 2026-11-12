package blue.anika.wolle.data.impersonation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import blue.anika.wolle.MainActivity
import blue.anika.wolle.data.api.AdminApi
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** The impersonation endpoints of `src/api_v2/admin.rs`. */
class ApiImpersonationBackend(private val api: AdminApi) : ImpersonationBackend {
    override suspend fun start(targetId: Long): StartedImpersonation {
        val data = api.startImpersonation(targetId).data
        return StartedImpersonation(
            token = data.token,
            session =
                ImpersonationSession(
                    targetId = data.user.id,
                    targetEmail = data.user.email,
                    targetName = data.user.name,
                    adminEmail = data.admin.email,
                    expiresAtSeconds = data.expiresAt,
                ),
        )
    }

    override suspend fun stop() {
        api.stopImpersonation()
    }
}

/** Deletes the impersonation's Room file and queued uploads from this app's private storage. */
class AndroidImpersonationDataWiper(private val context: Context) : ImpersonationDataWiper {
    override fun wipe(): Boolean =
        SessionNamespace.wipeImpersonationData(
            context::getDatabasePath,
            context.filesDir,
            context.cacheDir,
        )
}

/**
 * Restarts the process. The new process has to be started from a different one, otherwise it would
 * simply be the old process again: [ProcessRestartActivity] runs in its own `:restart` process,
 * starts the app's launcher activity and exits.
 */
class ProcessRestarter(private val context: Context) : AppRestarter {
    override fun restart(afterImpersonation: Boolean) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (afterImpersonation) launch.putExtra(MainActivity.EXTRA_AFTER_IMPERSONATION, true)
        val restart =
            Intent(context, ProcessRestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(ProcessRestartActivity.EXTRA_LAUNCH, launch)
        context.startActivity(restart)
        // The old process ends here; the restart activity opens the app in a fresh one.
        Runtime.getRuntime().exit(0)
    }
}

/** Starts the launcher intent it was given from the `:restart` process, then ends itself. */
class ProcessRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_LAUNCH, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_LAUNCH)
            }
        if (launch != null) startActivity(launch)
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val EXTRA_LAUNCH = "launch"
    }
}

/** Whether this is the short-lived `:restart` process, which must not run the app's start-up. */
fun Application.isRestartProcess(): Boolean {
    val name =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }
                .getOrDefault(packageName)
    return name.endsWith(RESTART_PROCESS_SUFFIX)
}

const val RESTART_PROCESS_SUFFIX = ":restart"

@Module
@InstallIn(SingletonComponent::class)
object ImpersonationModule {
    @Provides
    @Singleton
    fun provideImpersonationController(
        settings: SettingsRepository,
        api: AdminApi,
        @ApplicationContext context: Context,
    ): ImpersonationController =
        ImpersonationController(
            store = settings,
            backend = ApiImpersonationBackend(api),
            wiper = AndroidImpersonationDataWiper(context),
            restarter = ProcessRestarter(context),
        )
}
