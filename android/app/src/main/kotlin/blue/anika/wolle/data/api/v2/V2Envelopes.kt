package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.ApiErrorDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `src/api_v2/mod.rs`'s `Envelope<T>`: every v2 success body is `{ "data": ... }`. */
@Serializable data class V2Envelope<T>(val data: T)

/** `src/api_v2/{projects,yarns}.rs`'s `ListEnvelope<T>`: `{ "data": [...], "meta": {...} }`. */
@Serializable data class V2ListEnvelope<T>(val data: List<T>, val meta: V2PageMeta)

/** `PageMeta`. Page numbers are 1-based and the page size is fixed server-side (50). */
@Serializable
data class V2PageMeta(
    val page: Int,
    @SerialName("per_page") val perPage: Int,
    @SerialName("has_more") val hasMore: Boolean,
)

/**
 * A 409 from a v2 update: `{ "error": { "code": "conflict", "message": ..., "current": <detail> }
 * }`. Replaces v1's FastAPI-style `{ "detail": <detail> }`. Only `error.current` is generic; the
 * other error fields use [ApiErrorDto].
 */
@Serializable data class V2ConflictEnvelope<T>(val error: V2ConflictError<T>)

@Serializable
data class V2ConflictError<T>(
    val code: String? = null,
    val message: String? = null,
    val fields: Map<String, String>? = null,
    val current: T? = null,
)

/**
 * Decodes the `error.current` entity of a v2 409 body, tolerating a missing or malformed body by
 * returning null. The v2 counterpart of `parseConflictDetail` in `sync/WriteReplayWorker.kt`.
 */
internal inline fun <reified T> parseV2Conflict(json: Json, errorBody: String): T? =
    runCatching { json.decodeFromString<V2ConflictEnvelope<T>>(errorBody).error.current }
        .getOrNull()
