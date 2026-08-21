package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.MetaDto
import retrofit2.http.GET

/** `stricknani/routes/api/meta.py`. Unauthenticated - used for onboarding validation. */
interface MetaApi {
    @GET("api/v1/meta") suspend fun getMeta(): MetaDto
}
