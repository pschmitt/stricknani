package blue.anika.wolle.data.impersonation

import java.io.File

/**
 * An administrator acting as another account inside the app (`POST
 * /api/v2/admin/users/{id}/impersonation-token`). Not a secret: the credential itself lives only in
 * the encrypted settings store.
 *
 * [expiresAtSeconds] is the server's expiry (epoch seconds), one hour after the start like the web.
 */
data class ImpersonationSession(
    val targetId: Long,
    val targetEmail: String,
    val targetName: String,
    val adminEmail: String?,
    val expiresAtSeconds: Long,
) {
    fun isExpired(nowSeconds: Long): Boolean = nowSeconds >= expiresAtSeconds

    /** Whole seconds left, never negative. */
    fun secondsLeft(nowSeconds: Long): Long = (expiresAtSeconds - nowSeconds).coerceAtLeast(0)
}

/**
 * Whether this process runs as an impersonation. Fixed for the life of the process on purpose:
 * everything that belongs to one identity (the Room file, the queued uploads, the credential the
 * network layer sends) is chosen once at start, so no request can ever be sent under one identity
 * with another one's data. Entering or leaving an impersonation restarts the process.
 *
 * Set by [blue.anika.wolle.data.settings.SettingsRepository] before anything else reads it.
 */
object SessionMode {
    @Volatile
    var impersonating: Boolean = false
        private set

    fun set(impersonating: Boolean) {
        this.impersonating = impersonating
    }

    val databaseName: String
        get() = SessionNamespace.databaseName(impersonating)

    val uploadsDirectory: String
        get() = SessionNamespace.uploadsDirectory(impersonating)

    val imageCacheDirectory: String
        get() = SessionNamespace.imageCacheDirectory(impersonating)
}

/**
 * Where an identity keeps its data. The administrator's own account and an impersonation never
 * share a Room file (cache, tombstones, sync cursors and the queue of offline writes) or a queue of
 * picked uploads, so a queued write cannot be replayed under the wrong identity and an impersonated
 * account's data never reaches the administrator's own cache.
 */
object SessionNamespace {
    const val OWN_DATABASE = "stricknani.db"
    const val IMPERSONATION_DATABASE = "stricknani-impersonation.db"
    const val OWN_UPLOADS = "pending-uploads"
    const val IMPERSONATION_UPLOADS = "pending-uploads-impersonation"

    const val OWN_IMAGE_CACHE = "image_cache"
    const val IMPERSONATION_IMAGE_CACHE = "image_cache_impersonation"

    private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    fun databaseName(impersonating: Boolean): String =
        if (impersonating) IMPERSONATION_DATABASE else OWN_DATABASE

    fun uploadsDirectory(impersonating: Boolean): String =
        if (impersonating) IMPERSONATION_UPLOADS else OWN_UPLOADS

    fun imageCacheDirectory(impersonating: Boolean): String =
        if (impersonating) IMPERSONATION_IMAGE_CACHE else OWN_IMAGE_CACHE

    /**
     * Deletes everything an impersonation left on disk: its database (with the sidecar files), its
     * queued uploads and the pictures it cached. Only the impersonation's names are ever touched.
     * Returns whether nothing is left.
     */
    fun wipeImpersonationData(
        databaseFile: (String) -> File,
        filesDir: File,
        cacheDir: File,
    ): Boolean {
        var clean = true
        for (suffix in DATABASE_SUFFIXES) {
            val file = databaseFile(IMPERSONATION_DATABASE + suffix)
            if (file.exists() && !file.delete()) clean = false
        }
        val uploads = File(filesDir, IMPERSONATION_UPLOADS)
        if (uploads.exists() && !uploads.deleteRecursively()) clean = false
        val images = File(cacheDir, IMPERSONATION_IMAGE_CACHE)
        if (images.exists() && !images.deleteRecursively()) clean = false
        return clean
    }
}

/**
 * Impersonation sessions are online-only. A write is first applied to the local Room copy and
 * queued for replay, so "online-only" is enforced where the queue is written: while impersonating
 * and without a validated connection a write is refused (and its local change rolled back with the
 * transaction) instead of waiting in a queue that could outlive the session.
 */
class OfflineWriteGuard(
    private val impersonating: Boolean,
    private val hasValidatedInternet: () -> Boolean?,
) {
    /** Throws [ImpersonationOfflineException] when a write must not be queued right now. */
    fun checkQueueing() {
        if (impersonating && hasValidatedInternet() != true) throw ImpersonationOfflineException()
    }
}

/** An [java.io.IOException], so the screens treat it like any other offline failure. */
class ImpersonationOfflineException :
    java.io.IOException("Changes need a connection while acting as another account")

/**
 * The queue of offline writes of an impersonation session: every read and every update is the real
 * one, only adding a write is checked by [guard]. Plain delegation, so a new method of the DAO is
 * forwarded without being listed here.
 */
class OnlineOnlyPendingMutationDao(
    private val delegate: blue.anika.wolle.data.db.dao.PendingMutationDao,
    private val guard: OfflineWriteGuard,
) : blue.anika.wolle.data.db.dao.PendingMutationDao by delegate {
    override suspend fun insert(
        mutation: blue.anika.wolle.data.db.entity.PendingMutationEntity
    ): Long {
        guard.checkQueueing()
        return delegate.insert(mutation)
    }
}
