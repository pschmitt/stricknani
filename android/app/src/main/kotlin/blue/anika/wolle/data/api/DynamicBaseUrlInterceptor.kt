package blue.anika.wolle.data.api

import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Retrofit/Coil requests are built once against a placeholder host; this interceptor rewrites every
 * request to the user's currently configured Stricknani server so changing it in Settings doesn't
 * require rebuilding the whole OkHttp/Retrofit stack. Same pattern as the sibling apps (e.g.
 * nyetbox's `DynamicBaseUrlInterceptor`).
 */
class DynamicBaseUrlInterceptor
@Inject
constructor(private val settingsRepository: SettingsRepository) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val configured =
            settingsRepository.credentials.value.serverUrl.toHttpUrlOrNull()
                ?: return chain.proceed(request)

        val rewrittenUrl =
            request.url
                .newBuilder()
                .scheme(configured.scheme)
                .host(configured.host)
                .port(configured.port)
                .encodedPath(configured.encodedPath.trimEnd('/') + request.url.encodedPath)
                .build()
        return chain.proceed(request.newBuilder().url(rewrittenUrl).build())
    }
}
