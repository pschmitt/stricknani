package blue.anika.wolle.data.repository

import androidx.room.withTransaction
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.CurrentUser
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.db.entity.MutationEntityType
import blue.anika.wolle.data.db.entity.SyncStateEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Which items a sync pulls: only the user's own, or (administrators) everybody's. */
enum class SyncScope(val wire: String?) {
    /** The server default: send no `scope` parameter at all. */
    MINE(null),
    ALL("all"),
}

/** `sync_state` key holding the Mine/Everyone choice ("1" everyone, "0" mine); absent = default. */
const val EVERYONE_FILTER_KEY = "everyone_filter"

private const val PURGE_CHUNK = 500

/**
 * The `sync_state` key of one entity type's cursor under [scope]. Each scope has its own cursor, so
 * going from Mine to Everyone starts a full sync instead of resuming a cursor that never saw other
 * people's items.
 */
fun syncCursorKey(entityType: String, scope: SyncScope): String =
    if (scope == SyncScope.ALL) "$entityType@all" else entityType

/** Administrators default to Everyone until they pick Mine; everybody else is always Mine. */
fun effectiveScope(isAdmin: Boolean, stored: String?): SyncScope =
    if (isAdmin && stored != "0") SyncScope.ALL else SyncScope.MINE

/** What the Mine/Everyone chip shows: [available] only for administrators. */
data class EveryoneFilterState(val available: Boolean = false, val enabled: Boolean = false)

/**
 * The administrator's Mine/Everyone choice and everything that follows from it.
 *
 * Other users' projects and yarns are cached only while Everyone is on. Turning it off purges them
 * (rows without a queued local write; those stay until the write is replayed), turning it on resets
 * the `@all` cursors so the next sync is a full one. The choice lives in Room's `sync_state`, so it
 * is wiped with every other table on sign-out and cannot leak to the next account.
 */
@Singleton
class EveryoneScope
internal constructor(
    private val resolveUser: suspend () -> CurrentUser?,
    private val knownUser: () -> CurrentUser?,
    private val userFlow: Flow<CurrentUser?>,
    private val syncStateDao: SyncStateDao,
    private val projectDao: ProjectDao,
    private val yarnDao: YarnDao,
    private val pendingIds: suspend (entityType: String, ids: List<Int>) -> Set<Int>,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    @Inject
    constructor(
        database: AppDatabase,
        capabilities: ApiCapabilities,
        syncStateDao: SyncStateDao,
        projectDao: ProjectDao,
        yarnDao: YarnDao,
        pendingMutationDao: PendingMutationDao,
    ) : this(
        resolveUser = { capabilities.resolve().user },
        knownUser = { capabilities.current.user },
        userFlow = capabilities.state.map { it.user },
        syncStateDao = syncStateDao,
        projectDao = projectDao,
        yarnDao = yarnDao,
        pendingIds = { type, ids -> pendingMutationDao.getReplayableLocalIds(type, ids).toSet() },
        inTransaction = { block -> database.withTransaction { block() } },
    )

    /** The chip's state; the chip is hidden unless the user is an administrator. */
    val state: Flow<EveryoneFilterState> =
        combine(userFlow, syncStateDao.observeAll()) { user, rows ->
                val stored = rows.firstOrNull { it.entityType == EVERYONE_FILTER_KEY }?.cursor
                val admin = user?.isAdmin == true
                EveryoneFilterState(
                    available = admin,
                    enabled = effectiveScope(admin, stored) == SyncScope.ALL,
                )
            }
            .distinctUntilChanged()

    /**
     * The scope the next sync runs in. Asks the server who the user is first (throws like any other
     * sync call when that fails). When the answer is Mine, other people's cached items are purged,
     * which also covers a demoted administrator.
     */
    suspend fun forSync(): SyncScope {
        val user = resolveUser()
        val scope = effectiveScope(user?.isAdmin == true, storedChoice())
        if (scope == SyncScope.MINE && user != null) purgeForeign(user.id)
        return scope
    }

    /** Switches the chip: purges on the way to Mine, resets the `@all` cursors either way. */
    suspend fun setEnabled(enabled: Boolean) {
        val user = knownUser()
        inTransaction {
            syncStateDao.setCursor(SyncStateEntity(EVERYONE_FILTER_KEY, if (enabled) "1" else "0"))
            syncStateDao.deleteCursors(
                listOf(
                    syncCursorKey(MutationEntityType.PROJECT, SyncScope.ALL),
                    syncCursorKey(MutationEntityType.YARN, SyncScope.ALL),
                )
            )
            if (!enabled && user != null) purgeForeign(user.id)
        }
    }

    private suspend fun storedChoice(): String? = syncStateDao.getCursor(EVERYONE_FILTER_KEY)

    /** Removes cached projects and yarns of other users, except rows with queued local writes. */
    internal suspend fun purgeForeign(currentUserId: Long) {
        purge(MutationEntityType.PROJECT, projectDao.getForeignIds(currentUserId)) {
            projectDao.deleteByIds(it)
        }
        purge(MutationEntityType.YARN, yarnDao.getForeignIds(currentUserId)) {
            yarnDao.deleteByIds(it)
        }
    }

    private suspend fun purge(
        entityType: String,
        foreignIds: List<Int>,
        delete: suspend (List<Int>) -> Unit,
    ) {
        foreignIds.chunked(PURGE_CHUNK).forEach { chunk ->
            val pending = pendingIds(entityType, chunk)
            val removable = chunk.filterNot { it in pending }
            if (removable.isNotEmpty()) delete(removable)
        }
    }
}
