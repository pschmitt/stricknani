package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.CategoryCreateRequest
import blue.anika.wolle.data.api.dto.CategoryDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** `stricknani/routes/api/categories.py`. */
interface CategoriesApi {
    @GET("api/v1/categories") suspend fun listCategories(): List<CategoryDto>

    @POST("api/v1/categories")
    suspend fun createCategory(@Body request: CategoryCreateRequest): CategoryDto
}
