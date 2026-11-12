package blue.anika.wolle.data.api

import okhttp3.Interceptor
import okhttp3.Response

/** The request header that tells the server which client made a request (audit log, admin only). */
const val CLIENT_HEADER = "X-Stricknani-Client"

/**
 * `android/<versionName> (code <versionCode>; <debug|release>)`, the value of [CLIENT_HEADER]. The
 * server drops values outside its grammar, so the version is reduced to its charset and length
 * (`[0-9A-Za-z._+-]{1,32}`) here.
 */
fun clientHeaderValue(versionName: String, versionCode: Int, debug: Boolean): String {
    val version =
        versionName.takeIf { it.length <= 32 && it.matches(Regex("[0-9A-Za-z._+-]+")) } ?: "unknown"
    val flavour = if (debug) "debug" else "release"
    return "android/$version (code $versionCode; $flavour)"
}

/** Adds [CLIENT_HEADER] to every request of the API client. */
class ClientHeaderInterceptor(private val value: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(CLIENT_HEADER, value).build())
}
