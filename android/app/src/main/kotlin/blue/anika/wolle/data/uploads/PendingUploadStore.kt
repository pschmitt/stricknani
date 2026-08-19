package blue.anika.wolle.data.uploads

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/** Persists picker selections until the outbox has uploaded them successfully. */
@Singleton
class PendingUploadStore @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun copy(uri: Uri, altText: String = "", stepIndex: Int? = null): PendingUpload =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val fileName = queryFileName(uri) ?: "photo-${UUID.randomUUID()}.jpg"
            val contentType = resolver.getType(uri) ?: "application/octet-stream"
            val targetDirectory = File(context.filesDir, UPLOAD_DIRECTORY).apply { mkdirs() }
            val target = File(targetDirectory, "${UUID.randomUUID()}-${safeName(fileName)}")
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to read selected file" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            PendingUpload(
                path = target.absolutePath,
                fileName = fileName,
                contentType = contentType,
                altText = altText,
                stepIndex = stepIndex,
            )
        }

    fun multipart(upload: PendingUpload): MultipartBody.Part {
        val file = File(upload.path)
        require(file.isFile) { "Pending upload is missing: ${upload.path}" }
        val body = file.asRequestBody(upload.contentType.toMediaType())
        return MultipartBody.Part.createFormData("file", upload.fileName, body)
    }

    suspend fun delete(upload: PendingUpload) {
        withContext(Dispatchers.IO) { File(upload.path).delete() }
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor: Cursor? =
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
        return cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun safeName(name: String): String =
        name.substringAfterLast('/').replace(UNSAFE_FILENAME, "_").take(MAX_FILENAME_LENGTH)

    private companion object {
        const val UPLOAD_DIRECTORY = "pending-uploads"
        const val MAX_FILENAME_LENGTH = 120
        val UNSAFE_FILENAME = Regex("[^A-Za-z0-9._-]")
    }
}
