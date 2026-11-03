package blue.anika.wolle.data.oidc

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

internal fun retrofitHttp(status: Int, code: String?, retryAfter: String? = null): HttpException {
    val body =
        (if (code == null) "{}" else """{"error":{"code":"$code"}}""").toResponseBody(
            "application/json".toMediaType()
        )
    val raw =
        okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://s/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(status)
            .message("m")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
    return HttpException(Response.error<Any>(body, raw))
}
