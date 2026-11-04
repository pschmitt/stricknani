package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.api.dto.CategoryDto
import blue.anika.wolle.data.api.dto.CategoryUpdateRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** `src/api_v2/categories.rs`. Not wired in yet - see [ProjectsV2Api]. */
interface CategoriesV2Api {
    @GET("api/v2/categories") suspend fun listCategories(): V2Envelope<List<CategoryDto>>

    @POST("api/v2/categories")
    suspend fun createCategory(@Body request: CategoryCreateRequest): V2Envelope<CategoryDto>

    @PUT("api/v2/categories/{categoryId}")
    suspend fun updateCategory(
        @Path("categoryId") categoryId: Int,
        @Body request: CategoryUpdateRequest,
    ): V2Envelope<CategoryDto>

    @DELETE("api/v2/categories/{categoryId}")
    suspend fun deleteCategory(@Path("categoryId") categoryId: Int)
}
