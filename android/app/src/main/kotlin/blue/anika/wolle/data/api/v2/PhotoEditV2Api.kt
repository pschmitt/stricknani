package blue.anika.wolle.data.api.v2

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * `src/api_v2/media.rs`: rotate and crop a saved photo in place. Needs the network (never queued).
 * The id stays, the answer carries the new `url` and `thumbnail_url`.
 */
interface PhotoEditV2Api {
    @POST("api/v2/projects/{projectId}/images/{imageId}/edit")
    suspend fun editProjectImage(
        @Path("projectId") projectId: Int,
        @Path("imageId") imageId: Int,
        @Body body: PhotoEditRequest,
    ): V2Envelope<ImageDto>

    @POST("api/v2/yarns/{yarnId}/photos/{photoId}/edit")
    suspend fun editYarnPhoto(
        @Path("yarnId") yarnId: Int,
        @Path("photoId") photoId: Int,
        @Body body: PhotoEditRequest,
    ): V2Envelope<YarnPhotoDto>
}

/** [rotate] is clockwise degrees (0, 90, 180, 270); [crop] is optional. */
@Serializable data class PhotoEditRequest(val rotate: Int, val crop: PhotoCropDto? = null)

/** Fractions (0..1) of the image after the rotation. */
@Serializable
data class PhotoCropDto(val x: Float, val y: Float, val width: Float, val height: Float)
