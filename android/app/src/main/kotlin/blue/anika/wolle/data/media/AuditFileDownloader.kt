package blue.anika.wolle.data.media

import android.content.Context
import blue.anika.wolle.data.api.dto.AuditMediaDto
import blue.anika.wolle.di.MediaClient
import blue.anika.wolle.ui.admin.safeAuditFileName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a file an audit event refers to (a project attachment) into the cache so a viewer app can
 * open it. The request carries the stored token, so it works for administrator routes too.
 */
@Singleton
class AuditFileDownloader
@Inject
constructor(
    @MediaClient private val client: OkHttpClient,
    @ApplicationContext private val context: Context,
    private val resolver: MediaUrlResolver,
) {
    /** The downloaded file, or null when it is gone, too large or the request failed. */
    suspend fun download(media: AuditMediaDto): File? =
        withContext(Dispatchers.IO) {
            val url = resolver.resolve(media.url) ?: return@withContext null
            runCatching {
                    val dir = File(context.cacheDir, "audit-files").apply { mkdirs() }
                    val target = File(dir, safeAuditFileName(media))
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        val body = response.body
                        if (body.contentLength() > MAX_BYTES) return@runCatching null
                        body.byteStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    target
                }
                .getOrNull()
        }

    private companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
