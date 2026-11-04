package blue.anika.wolle.data.update.shizuku

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import blue.anika.wolle.data.update.PrivilegedInstaller
import blue.anika.wolle.data.update.PrivilegedState
import blue.anika.wolle.data.update.SelfUpdateInstaller
import blue.anika.wolle.data.update.privilegedState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.lang.reflect.InvocationTargetException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import timber.log.Timber

/**
 * Installs updates as the shell user through Shizuku (or Sui), so they are silent from the very
 * first one. The package installer is reached the way Obtainium and InstallerX do it: the system's
 * `package` binder, wrapped in a [ShizukuBinderWrapper] so calls run with Shizuku's uid, then
 * `IPackageInstaller` and its session binder wrapped the same way. Those are hidden APIs, so they
 * are used through reflection after lifting the hidden-API restrictions; this file is the only
 * place that knows about them.
 */
@Singleton
class ShizukuPrivilegedInstaller
@Inject
constructor(@ApplicationContext private val context: Context) : PrivilegedInstaller {

    private val _state = MutableStateFlow(PrivilegedState.UNAVAILABLE)
    override val state: StateFlow<PrivilegedState> = _state.asStateFlow()

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    override fun refresh() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted =
            alive &&
                runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                    .getOrDefault(false)
        val supported = !alive || runCatching { !Shizuku.isPreV11() }.getOrDefault(true)
        _state.value = privilegedState(managerInstalled(), alive, granted, supported)
    }

    override fun requestPermission() {
        runCatching {
                if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                    Shizuku.requestPermission(REQUEST_CODE)
                }
            }
            .onFailure { Timber.w(it, "Shizuku permission request failed") }
    }

    override fun managerLaunchIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE)

    private fun managerInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0) }.isSuccess

    override fun install(file: File, resultSender: IntentSender) {
        check(_state.value == PrivilegedState.READY) { "Shizuku is not ready" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        val installerBinder = privilegedBinder(packageInstallerService())
        val iInstaller = asInterface("android.content.pm.IPackageInstaller", installerBinder)
        val packageInstaller = newPackageInstaller(iInstaller)

        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                addInstallFlags(this, INSTALL_REPLACE_EXISTING)
            }
        val sessionId = packageInstaller.createSession(params)
        try {
            // IPackageInstaller.openSession(id) -> IPackageInstallerSession; the session only
            // answers its owner (shell), so its binder is wrapped as well.
            val iSession = call(iInstaller, "openSession", sessionId)
            val sessionBinder = privilegedBinder((iSession as IInterface).asBinder())
            val session =
                newSession(
                    asInterface("android.content.pm.IPackageInstallerSession", sessionBinder)
                )
            session.use {
                file.inputStream().use { input ->
                    it.openWrite("update.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        it.fsync(output)
                    }
                }
                it.commit(resultSender)
            }
        } catch (e: Throwable) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw e
        }
    }

    /** Hidden `SessionParams.installFlags`: lets a same-version build replace itself. */
    private fun addInstallFlags(params: PackageInstaller.SessionParams, flags: Int) {
        runCatching {
                val field =
                    PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
                field.isAccessible = true
                field.setInt(params, field.getInt(params) or flags)
            }
            .onFailure { Timber.w(it, "Could not set install flags") }
    }

    /** `IPackageManager.getPackageInstaller()` over Shizuku's uid; returns the installer binder. */
    private fun packageInstallerService(): IBinder {
        val pmBinder = privilegedBinder(SystemServiceHelper.getSystemService("package"))
        val iPackageManager = asInterface("android.content.pm.IPackageManager", pmBinder)
        return (call(iPackageManager, "getPackageInstaller") as IInterface).asBinder()
    }

    private fun privilegedBinder(binder: IBinder): IBinder = ShizukuBinderWrapper(binder)

    /** `PackageInstaller(IPackageInstaller, installerPackageName, [attributionTag,] userId)`. */
    private fun newPackageInstaller(iInstaller: Any): PackageInstaller {
        val iface = Class.forName("android.content.pm.IPackageInstaller")
        val constructor =
            PackageInstaller::class.java.declaredConstructors.first {
                it.parameterTypes.firstOrNull() == iface
            }
        var strings = 0
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    iface -> iInstaller
                    String::class.java -> (if (strings++ == 0) installerName() else null)
                    Int::class.javaPrimitiveType -> Process.myUserHandle().hashCode()
                    else -> error("Unexpected PackageInstaller constructor parameter $type")
                }
            }
        constructor.isAccessible = true
        return unwrap { constructor.newInstance(*args.toTypedArray()) as PackageInstaller }
    }

    private fun newSession(iSession: Any): PackageInstaller.Session {
        val iface = Class.forName("android.content.pm.IPackageInstallerSession")
        val constructor =
            PackageInstaller.Session::class.java.getDeclaredConstructor(iface).apply {
                isAccessible = true
            }
        return unwrap { constructor.newInstance(iSession) }
    }

    /** The installer package the session is recorded under: the shell (or root) Shizuku runs as. */
    private fun installerName(): String =
        if (runCatching { Shizuku.getUid() }.getOrDefault(SHELL_UID) == 0) "root"
        else "com.android.shell"

    private fun asInterface(className: String, binder: IBinder): Any {
        val stub = Class.forName("$className\$Stub")
        return unwrap { stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!! }
    }

    private fun call(target: Any, name: String, vararg args: Any): Any {
        val method =
            target.javaClass.methods.first {
                it.name == name && it.parameterTypes.size == args.size
            }
        return unwrap { method.invoke(target, *args)!! }
    }

    private inline fun <T> unwrap(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }

    private companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 0x5112
        const val SHELL_UID = 2000
        const val INSTALL_REPLACE_EXISTING = 0x00000002
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ShizukuBindingsModule {
    @Binds
    @SelfUpdateInstaller
    abstract fun installer(impl: ShizukuPrivilegedInstaller): PrivilegedInstaller
}
