package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.YarnDto
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryImageEditingTest {
    @Test
    fun `removing a project image keeps other gallery images`() {
        val project =
            ProjectDto(
                id = 1,
                name = "Project",
                isAiEnhanced = false,
                isFavorite = false,
                createdAt = "2026-10-31T00:00:00Z",
                updatedAt = "2026-10-31T00:00:00Z",
                images = listOf(projectImage(1), projectImage(2), projectImage(3)),
            )

        assertEquals(listOf(1, 3), removeProjectImage(project, 2).images.map { it.id })
    }

    @Test
    fun `removing a yarn photo keeps other gallery photos`() {
        val yarn =
            YarnDto(
                id = 1,
                name = "Yarn",
                isAiEnhanced = false,
                isFavorite = false,
                createdAt = "2026-10-31T00:00:00Z",
                updatedAt = "2026-10-31T00:00:00Z",
                photos = listOf(yarnPhoto(1), yarnPhoto(2), yarnPhoto(3)),
            )

        assertEquals(listOf(1, 3), removeYarnPhoto(yarn, 2).photos.map { it.id })
    }

    private fun projectImage(id: Int) =
        ImageDto(
            id = id,
            url = "/image/$id",
            thumbnailUrl = "/image/$id/thumb",
            altText = "",
            imageType = "title",
            isTitleImage = false,
            isStitchSample = false,
        )

    private fun yarnPhoto(id: Int) =
        YarnPhotoDto(
            id = id,
            url = "/photo/$id",
            thumbnailUrl = "/photo/$id/thumb",
            altText = "",
            isPrimary = false,
        )
}
