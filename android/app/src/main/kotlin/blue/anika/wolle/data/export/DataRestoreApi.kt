package blue.anika.wolle.data.export

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * `POST /api/v2/account/import` (`docs/data-export.md`, "Restore"): the archive itself is the
 * request body. The body is a [StreamingRequestBody], so Retrofit never buffers the archive. The
 * response stays a raw [ResponseBody]; [parseRestoreSummary] and [restoreFailureFor] decode it.
 */
interface DataRestoreApi {
    @POST("api/v2/account/import")
    suspend fun import(@Body archive: RequestBody): Response<ResponseBody>
}
