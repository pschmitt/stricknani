package blue.anika.wolle.localserver

import android.content.Context
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/**
 * The local-only mode (T130, `docs/local-only.md`): the Stricknani server runs inside the app, on
 * loopback, with its database and photos in the app's private storage. The rest of the app talks to
 * it like to any server; [ensureStarted] runs before every request
 * ([blue.anika.wolle.data.api.DynamicBaseUrlInterceptor]), so a fresh process (app start, a sync
 * worker) brings the server up on demand.
 */
@Singleton
class LocalServer
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val lock = Any()
    @Volatile private var running = false

    /** This build carries the server library. */
    val isAvailable: Boolean
        get() = LocalServerNative.available

    private val dataDir: File
        get() = File(context.filesDir, DATA_DIR)

    /** Switch the app to the local server (onboarding). Throws when it cannot start. */
    fun enable() {
        synchronized(lock) {
            val (url, token) = startNative(preferredPort = 0)
            settings.saveLocal(url, token)
            running = true
        }
    }

    /**
     * Start the server if the app is in local mode and it is not running in this process yet, and
     * keep the stored address in step with the port it got. False when not in local mode.
     */
    fun ensureStarted(): Boolean {
        if (!settings.isLocalMode) return false
        if (running) return true
        synchronized(lock) {
            if (running) return true
            val stored = settings.credentials.value
            val preferred = stored.serverUrl.toHttpUrlOrNull()?.port ?: 0
            val (url, token) = startNative(preferred)
            if (stored.serverUrl != url || stored.apiToken != token) settings.saveLocal(url, token)
            running = true
            return true
        }
    }

    private fun startNative(preferredPort: Int): Pair<String, String> {
        check(isAvailable) { "local server not packaged" }
        Timber.tag(LOG_TAG).i("Starting the in-app server")
        val reply = LocalServerNative.start(dataDir.absolutePath, preferredPort)
        Timber.tag(LOG_TAG).i("The in-app server listens on port %s", reply.substringBefore('\n'))
        val port = reply.substringBefore('\n')
        val token = reply.substringAfter('\n')
        return "http://127.0.0.1:$port" to token
    }

    companion object {
        /** Logcat tag of the local-only mode (read by `LocalModeBackupE2eTest` on failure). */
        const val LOG_TAG = "StricknaniLocal"
        private const val DATA_DIR = "local-server"
    }
}
