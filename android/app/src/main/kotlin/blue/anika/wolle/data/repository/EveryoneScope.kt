package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.CurrentUser
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

/**
 * Administrators always sync everybody's items (the whole stash is available offline); everybody
 * else only ever syncs their own. The Mine/Everyone choice is a view filter, see [viewsEveryone].
 */
fun syncScopeFor(isAdmin: Boolean): SyncScope = if (isAdmin) SyncScope.ALL else SyncScope.MINE

/** Whether lists show everybody's items: administrators until they pick Mine; never others. */
fun viewsEveryone(isAdmin: Boolean, stored: String?): Boolean = isAdmin && stored != "0"

/** Keeps [this] rows that [hiddenFor]'s user may see: everything when null, else own rows only. */
fun <T> List<T>.visibleTo(hiddenFor: Long?, ownerOf: (T) -> Long?): List<T> =
    if (hiddenFor == null) this else filter { ownerOf(it).let { o -> o == null || o == hiddenFor } }

/** What the Mine/Everyone chip shows: [available] only for administrators. */
data class EveryoneFilterState(val available: Boolean = false, val enabled: Boolean = false)

/**
 * The administrator's Mine/Everyone choice.
 *
 * An administrator syncs every user's projects and yarns into Room whatever the choice is, so each
 * item opens offline. Mine | Everyone only filters list queries ([listFilter]); it never purges the
 * cache and never changes the sync scope. A user who is not (or no longer) an administrator gets
 * other users' cached rows purged on the next sync (rows with a queued local write stay until it is
 * replayed). The choice lives in Room's `sync_state`, so it is wiped with every other table on
 * sign-out and cannot leak to the next account.
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
) {
    @Inject
    constructor(
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
    )

    /** The chip's state; the chip is hidden unless the user is an administrator. */
    val state: Flow<EveryoneFilterState> =
        combine(userFlow, syncStateDao.observeAll()) { user, rows ->
                val stored = rows.firstOrNull { it.entityType == EVERYONE_FILTER_KEY }?.cursor
                val admin = user?.isAdmin == true
                EveryoneFilterState(available = admin, enabled = viewsEveryone(admin, stored))
            }
            .distinctUntilChanged()

    /**
     * The user id whose own items lists are restricted to (an administrator on Mine), or null when
     * lists show every cached row. Apply it with [visibleTo]; detail screens ignore it.
     */
    val listFilter: Flow<Long?> =
        combine(userFlow, syncStateDao.observeAll()) { user, rows ->
                val stored = rows.firstOrNull { it.entityType == EVERYONE_FILTER_KEY }?.cursor
                val admin = user?.isAdmin == true
                if (admin && !viewsEveryone(admin, stored)) user.id else null
            }
            .distinctUntilChanged()

    /**
     * The scope the next sync runs in. Asks the server who the user is first (throws like any other
     * sync call when that fails). An administrator syncs `all`; the old Mine cursors are dropped so
     * a later demotion starts from a full sync. Anyone else syncs Mine and other people's cached
     * items are purged, which covers a demoted administrator. An administrator coming from a Mine
     * install has no `@all` cursor yet, so the first sync after the update is a full one (a cursor
     * the server refuses is handled by `runScopedV2Sync`).
     */
    suspend fun forSync(): SyncScope {
        val user = resolveUser()
        val scope = syncScopeFor(user?.isAdmin == true)
        if (scope == SyncScope.ALL) {
            syncStateDao.deleteCursors(
                listOf(
                    syncCursorKey(MutationEntityType.PROJECT, SyncScope.MINE),
                    syncCursorKey(MutationEntityType.YARN, SyncScope.MINE),
                )
            )
        } else if (user != null) {
            purgeForeign(user.id)
        }
        return scope
    }

    /** Switches the view filter only: no purge, no cursor change. */
    suspend fun setEnabled(enabled: Boolean) {
        syncStateDao.setCursor(SyncStateEntity(EVERYONE_FILTER_KEY, if (enabled) "1" else "0"))
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
