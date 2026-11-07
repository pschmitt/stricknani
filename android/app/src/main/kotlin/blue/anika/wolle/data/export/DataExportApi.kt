package blue.anika.wolle.data.export

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * `GET /api/v2/account/export` (`docs/data-export.md`): everything the account can read as one
 * tar.gz archive file, streamed without a `Content-Length`. [Streaming] keeps Retrofit from
 * buffering the body.
 */
interface DataExportApi {
    @Streaming
    @GET("api/v2/account/export")
    suspend fun export(@Query("archive") archive: String? = null): Response<ResponseBody>
}
