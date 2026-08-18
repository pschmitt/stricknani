package blue.anika.wolle.data.media

import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a media path from a DTO (`/media/...`, relative - see `di/NetworkModule.kt`'s
 * `MediaClient` kdoc for why) into an absolute URL Coil can load, using the currently configured
 * server. Passes an already-absolute URL through untouched.
 */
@Singleton
class MediaUrlResolver @Inject constructor(private val settingsRepository: SettingsRepository) {
    fun resolve(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = settingsRepository.credentials.value.serverUrl.trimEnd('/')
        return base + (if (path.startsWith("/")) path else "/$path")
    }
}
