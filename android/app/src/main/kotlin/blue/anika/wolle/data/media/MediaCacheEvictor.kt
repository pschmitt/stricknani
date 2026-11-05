package blue.anika.wolle.data.media

import android.content.Context
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes cached media of an item the user lost access to (a sync tombstone), so the images stay
 * unreachable after the share ends: the server refuses them (403) and the device no longer holds a
 * copy.
 */
interface MediaCacheEvictor {
    /** [paths] are server media paths as found in the DTOs (`/media/...`). */
    suspend fun evict(paths: Collection<String>)
}

@Singleton
class CoilMediaCacheEvictor
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val resolver: MediaUrlResolver,
) : MediaCacheEvictor {
    override suspend fun evict(paths: Collection<String>) {
        val loader = SingletonImageLoader.get(context)
        paths
            .mapNotNull { resolver.resolve(it) }
            .distinct()
            .forEach { url ->
                loader.diskCache?.remove(url)
                loader.memoryCache?.remove(MemoryCache.Key(url))
            }
    }
}
