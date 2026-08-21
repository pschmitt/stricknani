package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * The web import route returns JSON even though it lives outside the versioned API namespace.
 *
 * The server accepts the same Bearer token used by the app once the route is exposed to API-token
 * authentication. Keeping this interface separate from [ProjectsApi] makes that boundary explicit
 * and lets the app report a useful error on older servers instead of pretending the import worked.
 */
interface ProjectImportApi {
    @FormUrlEncoded
    @POST("projects/import")
    suspend fun importProject(
        @Field("type") type: String = "url",
        @Field("url") url: String,
        @Field("use_ai") useAi: Boolean = false,
    ): ProjectImportResponseDto
}
