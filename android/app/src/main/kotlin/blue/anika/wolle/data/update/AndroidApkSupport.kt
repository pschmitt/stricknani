package blue.anika.wolle.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.pm.PackageInfoCompat
import blue.anika.wolle.BuildConfig
import blue.anika.wolle.di.ValidationClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Downloads over HTTPS from GitHub only: every hop (the release URL and the CDN redirect behind it)
 * must be a GitHub host on HTTPS, and a redirect never downgrades to HTTP. Uses the unauthenticated
 * [ValidationClient] - the main client would send the Stricknani PAT to github.com.
 */
class OkHttpApkFetcher @Inject constructor(@ValidationClient baseClient: OkHttpClient) :
    ApkFetcher {

    private val client =
        baseClient
            .newBuilder()
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps || !isTrustedRedirectHost(url.host)) {
                    throw IOException("Refusing to download from ${url.host}")
                }
                chain.proceed(chain.request())
            }
            .build()

    override suspend fun fetchText(url: String): String =
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "GitHub returned ${response.code}" }
                response.body.string()
            }
        }

    override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "GitHub returned ${response.code}" }
                val total = response.body.contentLength()
                var done = 0L
                var reported = 0L
                try {
                    response.body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                context.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - reported >= REPORT_EVERY_BYTES) {
                                    reported = done
                                    onProgress(done, total)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (
                        e.message?.contains("ENOSPC") == true ||
                            e.message?.contains("No space left", ignoreCase = true) == true
                    ) {
                        throw InsufficientStorageException(e.message)
                    }
                    throw e
                }
                if (total > 0) check(done == total) { "Download ended early" }
                onProgress(done, total)
            }
        }
    }

    override fun freeBytes(dir: File): Long = StatFs(dir.absolutePath).availableBytes

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val REPORT_EVERY_BYTES = 128 * 1024
    }
}

class AndroidApkInspector @Inject constructor(@ApplicationContext private val context: Context) :
    ApkInspector {

    private val pm: PackageManager
        get() = context.packageManager

    override fun installed(): ApkFacts? =
        runCatching { pm.getPackageInfo(context.packageName, signatureFlags()) }
            .getOrNull()
            ?.let(::facts)

    override fun archive(file: File): ApkFacts? =
        runCatching { pm.getPackageArchiveInfo(file.absolutePath, signatureFlags()) }
            .getOrNull()
            ?.let(::facts)

    override fun canRequestInstalls(): Boolean =
        canRequestInstallsSafely(BuildConfig.SELF_UPDATE_ENABLED) { pm.canRequestPackageInstalls() }

    override fun canInstallSilently(): Boolean =
        silentInstallPossible(
            Build.VERSION.SDK_INT,
            canRequestInstalls(),
            installerOfRecord(),
            context.packageName,
        )

    private fun installerOfRecord(): String? =
        runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
                }
            }
            .getOrNull()

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun facts(info: android.content.pm.PackageInfo): ApkFacts {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }
        return ApkFacts(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            signerDigests = signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet(),
        )
    }
}

/**
 * `canRequestPackageInstalls()` throws without the `REQUEST_INSTALL_PACKAGES` permission, which
 * only APK builds with self-update declare (Play bundles compile it out). Those builds can never
 * install, so they answer false without asking; a revoked or missing permission is also false.
 */
internal fun canRequestInstallsSafely(selfUpdateEnabled: Boolean, query: () -> Boolean): Boolean {
    if (!selfUpdateEnabled) return false
    return try {
        query()
    } catch (_: SecurityException) {
        false
    }
}

/**
 * Stages the APK in a [PackageInstaller] session; the answer arrives at [InstallStatusReceiver].
 */
class PackageInstallerSessionInstaller
@Inject
constructor(@ApplicationContext private val context: Context) : ApkSessionInstaller {

    override suspend fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        // A session left behind by an earlier attempt only pins storage.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }

        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Silent when the system allows it (see silentInstallPossible); otherwise
                    // the commit answers STATUS_PENDING_USER_ACTION and the user confirms once.
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("update.apk", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            UpdateInstalledMarker.set(context)
            val sender = installStatusSender(context, sessionId)
            session.commit(sender)
        }
    }
}

/**
 * Where an install session reports to: [InstallStatusReceiver], through a mutable PendingIntent.
 */
internal fun installStatusSender(context: Context, requestCode: Int): IntentSender {
    val intent = Intent(context, InstallStatusReceiver::class.java).setPackage(context.packageName)
    // Mutable: the system fills in the status extras.
    return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        .intentSender
}

/** Reinstalls the APK this process runs from through [PrivilegedInstaller] (developer action). */
internal suspend fun PrivilegedInstaller.installRunningBuild(context: Context) =
    withContext(Dispatchers.IO) {
        check(state.value == PrivilegedState.READY) { "Shizuku is not ready" }
        UpdateInstalledMarker.set(context)
        install(File(context.applicationInfo.sourceDir), installStatusSender(context, 0x5113))
    }

/**
 * Sends the APK through Shizuku when the user enabled it and it is ready, and through a regular
 * [PackageInstaller] session otherwise - including when the privileged attempt fails outright.
 */
class RoutingSessionInstaller
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val regular: PackageInstallerSessionInstaller,
    private val resolver: InstallPathResolver,
) : ApkSessionInstaller {

    override suspend fun isPrivileged(): Boolean = resolver.privilegedSelected()

    override suspend fun commit(file: File) {
        if (resolver.privilegedSelected()) {
            try {
                UpdateInstalledMarker.set(context)
                resolver.privileged.install(file, installStatusSender(context, PRIVILEGED_REQUEST))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Shizuku install failed, falling back to PackageInstaller")
                UpdateInstalledMarker.clear(context)
                resolver.privileged.refresh()
            }
        }
        regular.commit(file)
    }

    private companion object {
        const val PRIVILEGED_REQUEST = 0x5112
    }
}

/** Receives the [PackageInstaller] session result. */
@AndroidEntryPoint
class InstallStatusReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: UpdateInstallController
    @Inject lateinit var notifier: UpdateNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent) ?: return
                controller.onInstallStatus(InstallStatus.NeedsConfirmation(confirm))
                // A visible screen opens the dialog itself; otherwise ask through a notification.
                if (!controller.uiAttached) notifier.showConfirmation(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> controller.onInstallStatus(InstallStatus.Success)
            else -> {
                UpdateInstalledMarker.clear(context)
                val cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED
                controller.onInstallStatus(InstallStatus.Failure(message, cancelled))
                notifier.showFailure(controller.state.value)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}

/**
 * Android kills the app while it installs itself, so nobody is left to say it worked. This marker
 * (set when the session is committed) lets [UpdateInstalledReceiver] tell an in-app update from an
 * install by other means after the new version starts.
 */
internal object UpdateInstalledMarker {
    private fun file(context: Context) = File(context.filesDir, "update-installed.marker")

    fun set(context: Context) {
        runCatching { file(context).writeText("1") }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun consume(context: Context): Boolean = file(context).exists().also { clear(context) }
}

/** The new version's first sign of life: a "updated, tap to open" notification. */
@AndroidEntryPoint
class UpdateInstalledReceiver : BroadcastReceiver() {
    @Inject lateinit var notifier: UpdateNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (UpdateInstalledMarker.consume(context)) notifier.showInstalled()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateBindingsModule {
    @Binds abstract fun fetcher(impl: OkHttpApkFetcher): ApkFetcher

    @Binds abstract fun inspector(impl: AndroidApkInspector): ApkInspector

    @Binds abstract fun installer(impl: RoutingSessionInstaller): ApkSessionInstaller

    companion object {
        @Provides
        @Singleton
        fun workDir(@ApplicationContext context: Context): UpdateWorkDir =
            UpdateWorkDir(File(context.cacheDir, "app-update"))
    }
}
