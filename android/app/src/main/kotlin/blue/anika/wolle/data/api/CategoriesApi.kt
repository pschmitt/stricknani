package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.api.dto.CategoryDto
import blue.anika.wolle.data.api.dto.CategoryUpdateRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** `stricknani/routes/api/categories.py`. */
interface CategoriesApi {
    @GET("api/v1/categories") suspend fun listCategories(): List<CategoryDto>

    @POST("api/v1/categories")
    suspend fun createCategory(@Body request: CategoryCreateRequest): CategoryDto

    @PUT("api/v1/categories/{categoryId}")
    suspend fun updateCategory(
        @Path("categoryId") categoryId: Int,
        @Body request: CategoryUpdateRequest,
    ): CategoryDto

    @DELETE("api/v1/categories/{categoryId}")
    suspend fun deleteCategory(@Path("categoryId") categoryId: Int)
}
