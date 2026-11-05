package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepDto
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMediaOrderRebaseTest {
    @Test
    fun `allows own image order and timestamp change`() {
        val before = project()
        val reordered = before.images.reversed()

        val request =
            ProjectWriteRequest(
                name = "Local edit",
                category = "Cardigan",
                description = "Local description",
                imageUrls = listOf("local-image"),
                expectedUpdatedAt = before.updatedAt,
            )

        assertTrue(
            request.rebaseExpectedUpdatedAtAfterMediaOrder(
                before,
                before.copy(updatedAt = "server-v2", images = reordered),
            ) == request.copy(expectedUpdatedAt = "server-v2")
        )
    }

    @Test
    fun `rejects a queued update based on an older timestamp`() {
        val before = project()

        assertTrue(
            ProjectWriteRequest(name = "Local edit", expectedUpdatedAt = "server-v0")
                .rebaseExpectedUpdatedAtAfterMediaOrder(
                    before,
                    before.copy(updatedAt = "server-v2", images = before.images.reversed()),
                ) == null
        )
    }

    @Test
    fun `rejects remote description change`() {
        val before = project()

        assertTrue(
            ProjectWriteRequest(name = "Local edit", expectedUpdatedAt = before.updatedAt)
                .rebaseExpectedUpdatedAtAfterMediaOrder(
                    before,
                    before.copy(
                        updatedAt = "server-v2",
                        images = before.images.reversed(),
                        description = "Remote edit",
                    ),
                ) == null
        )
    }

    @Test
    fun `rejects remote step change`() {
        val before = project()

        assertTrue(
            ProjectWriteRequest(name = "Local edit", expectedUpdatedAt = before.updatedAt)
                .rebaseExpectedUpdatedAtAfterMediaOrder(
                    before,
                    before.copy(
                        updatedAt = "server-v2",
                        images = before.images.reversed(),
                        steps = listOf(StepDto(id = 4, title = "Remote step", stepNumber = 1)),
                    ),
                ) == null
        )
    }

    private fun project() =
        ProjectDto(
            id = 7,
            name = "Cardigan",
            isAiEnhanced = false,
            isFavorite = false,
            createdAt = "server-created",
            updatedAt = "server-v1",
            images =
                listOf(
                    image(1, position = 0, primary = true),
                    image(2, position = 1, primary = false),
                ),
        )

    private fun image(id: Int, position: Int, primary: Boolean) =
        ImageDto(
            id = id,
            url = "image-$id",
            thumbnailUrl = "thumb-$id",
            altText = "",
            imageType = "title",
            isTitleImage = true,
            isStitchSample = false,
            position = position,
            isPrimary = primary,
        )
}
