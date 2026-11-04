package blue.anika.wolle.data.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** How opening one project or yarn by id went when the cache did not (or might not) have it. */
sealed interface ItemLoad {
    /** The row is in Room (it was, or the fetch just put it there). */
    data object Available : ItemLoad

    /**
     * The server says the item does not exist (404), or it is queued for deletion on this device.
     */
    data object Gone : ItemLoad

    /** Not cached and the server cannot be reached: the item is not available offline. */
    data object Offline : ItemLoad

    /** The server refused or failed (403, 5xx); retrying may help. */
    data class Failed(val status: Int?) : ItemLoad
}

/**
 * Cache-miss fallback for opening an item by id. Administrators sync everybody's items, so this
 * only matters right after a scope upgrade, before the first full sync, or for an item created
 * since the last sync. It never fetches an item that is queued for deletion locally (the fetch
 * would bring the row back) and never touches negative offline-placeholder ids.
 */
internal suspend fun loadIfMissing(
    id: Int,
    isCached: suspend () -> Boolean,
    isDeletedLocally: suspend () -> Boolean,
    fetchAndCache: suspend () -> Unit,
): ItemLoad {
    if (isCached()) return ItemLoad.Available
    if (id <= 0 || isDeletedLocally()) return ItemLoad.Gone
    return try {
        fetchAndCache()
        ItemLoad.Available
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: HttpException) {
        if (failure.code() == 404) ItemLoad.Gone else ItemLoad.Failed(failure.code())
    } catch (failure: IOException) {
        ItemLoad.Offline
    } catch (failure: Exception) {
        ItemLoad.Failed(null)
    }
}
