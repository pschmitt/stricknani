package blue.anika.wolle.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/v1/meta` - `stricknani/routes/api/schemas.py`'s `MetaResponse`. */
@Serializable data class MetaDto(val version: String, @SerialName("build_id") val buildId: String)
