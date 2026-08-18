package blue.anika.wolle.data.api.dto

import kotlinx.serialization.Serializable

/** `stricknani/routes/api/schemas.py`'s `CategoryResponse`. */
@Serializable data class CategoryDto(val id: Int, val name: String)

/** `stricknani/routes/api/schemas.py`'s `CategoryCreateRequest`. */
@Serializable data class CategoryCreateRequest(val name: String)
