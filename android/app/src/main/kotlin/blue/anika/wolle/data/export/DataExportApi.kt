package blue.anika.wolle.data.export

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * `GET /api/v2/account/export` (`docs/data-export.md`): everything the account can read as one
 * tar.gz archive file, streamed without a `Content-Length`. [Streaming] keeps Retrofit from
 * buffering the body. An encrypted download takes two steps, see [createEncryptedExport].
 */
interface DataExportApi {
    @Streaming
    @GET("api/v2/account/export")
    suspend fun export(@Query("archive") archive: String? = null): Response<ResponseBody>

    /**
     * Step one of an encrypted download: `POST` `{archive, passphrase}`, answered `201` with a
     * ticket URL ([parseExportTicketUrl]). The passphrase is only in the body.
     */
    @POST("api/v2/account/export")
    suspend fun createEncryptedExport(@Body body: RequestBody): Response<ResponseBody>

    /** Step two: streams the ticket [url] (relative to the server) with the same credentials. */
    @Streaming @GET suspend fun downloadTicket(@Url url: String): Response<ResponseBody>
}
