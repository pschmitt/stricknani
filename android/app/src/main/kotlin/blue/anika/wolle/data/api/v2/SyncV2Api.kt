package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.CategoryDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * `src/api_v2/sync.rs`. Query: `since` (a previous `server_time`, verbatim) or `cursor` (a previous
 * `next_cursor`), never both; `limit` 1..50 (default 50, and part of the cursor). Categories sync
 * the same way; servers without that route fall back to [blue.anika.wolle.data.api.SyncApi].
 */
interface SyncV2Api {
    @GET("api/v2/sync/projects")
    suspend fun syncProjects(
        @Query("since") since: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): V2Envelope<V2SyncPageDto<ProjectV2Dto>>

    @GET("api/v2/sync/yarns")
    suspend fun syncYarns(
        @Query("since") since: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): V2Envelope<V2SyncPageDto<YarnV2Dto>>

    @GET("api/v2/sync/categories")
    suspend fun syncCategories(
        @Query("since") since: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): V2Envelope<V2SyncPageDto<CategoryDto>>
}

/** `{data:{updated, deleted_ids, server_time, full_resync_required, has_more, next_cursor}}`. */
@Serializable
data class V2SyncPageDto<T>(
    val updated: List<T> = emptyList(),
    @SerialName("deleted_ids") val deletedIds: List<Int> = emptyList(),
    @SerialName("server_time") val serverTime: String,
    @SerialName("full_resync_required") val fullResyncRequired: Boolean = false,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** What a complete v2 sync run produced. */
data class V2SyncOutcome(
    /** Store as the next `since`, verbatim. */
    val serverTime: String,
    /** The server asked for a clean refetch, so [seenIds] is the complete server-side id set. */
    val fullResync: Boolean,
    /** Ids delivered in the final pass (only filled for a [fullResync]). */
    val seenIds: Set<Int>,
)

class V2SyncProtocolException(message: String) : RuntimeException(message)

private const val MAX_PAGES = 10_000

/**
 * Runs one v2 delta sync: the first request uses [since], further pages use `next_cursor` while
 * `has_more`. Each page goes to [onPage] as it arrives, so memory stays bounded; the caller stores
 * the returned `serverTime` only after the whole run succeeded, so an interrupted run is simply
 * repeated from the old cursor (upserts and deletes are idempotent).
 *
 * `full_resync_required` discards the cursor: the run restarts once without `since` and the
 * [V2SyncOutcome] reports the complete set of ids so the caller can drop what the server no longer
 * has. The restart happens at most once, so a server that keeps the flag set cannot loop us.
 */
internal suspend fun <T> runV2Sync(
    since: String?,
    idOf: (T) -> Int,
    fetch: suspend (since: String?, cursor: String?) -> V2SyncPageDto<T>,
    onPage: suspend (updated: List<T>, deletedIds: List<Int>) -> Unit,
): V2SyncOutcome {
    var fullResync = false
    var startSince = since
    while (true) {
        val seen = mutableSetOf<Int>()
        var cursor: String? = null
        var serverTime: String? = null
        var pages = 0
        var restart = false
        do {
            val page = fetch(if (cursor == null) startSince else null, cursor)
            if (page.fullResyncRequired && !fullResync) {
                fullResync = true
                startSince = null
                restart = true
                break
            }
            if (++pages > MAX_PAGES) throw V2SyncProtocolException("sync did not terminate")
            if (serverTime == null) serverTime = page.serverTime
            if (fullResync) page.updated.mapTo(seen, idOf)
            onPage(page.updated, page.deletedIds)
            val next = page.nextCursor
            if (page.hasMore && (next == null || next == cursor))
                throw V2SyncProtocolException("has_more without a new next_cursor")
            cursor = if (page.hasMore) next else null
        } while (cursor != null)
        if (restart) continue
        return V2SyncOutcome(serverTime!!, fullResync, if (fullResync) seen else emptySet())
    }
}

/** Local server-known ids to drop after a full resync: not delivered, not queued locally. */
internal fun staleAfterFullResync(
    localIds: Collection<Int>,
    seen: Set<Int>,
    pendingLocalIds: Set<Int>,
): List<Int> = localIds.filter { it > 0 && it !in seen && it !in pendingLocalIds }
