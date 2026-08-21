package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ProjectImportApi
import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import blue.anika.wolle.data.api.dto.ProjectImportStepDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectImporterTest {
    @Test
    fun `maps web importer response into the offline project write model`() = runTest {
        val importer =
            ApiProjectImporter(
                object : ProjectImportApi {
                    override suspend fun importProject(
                        type: String,
                        url: String,
                        useAi: Boolean,
                    ) =
                        ProjectImportResponseDto(
                            title = "  Textured cardigan ",
                            category = "Sweaters",
                            needles = "4 mm",
                            description = "Description",
                            tags = listOf("winter", " "),
                            link = null,
                            imageUrls = listOf("https://example.com/photo.jpg"),
                            steps =
                                listOf(
                                    ProjectImportStepDto(
                                        stepNumber = null,
                                        title = null,
                                        description = "Cast on",
                                    )
                                ),
                        )
                }
            )

        val result = importer.importFromUrl("https://example.com/pattern")

        assertEquals("Textured cardigan", result.request.name)
        assertEquals("Sweaters", result.request.category)
        assertEquals("https://example.com/pattern", result.request.link)
        assertEquals(listOf("winter"), result.request.tags)
        assertEquals(1, result.request.steps.size)
        assertEquals("Step 1", result.request.steps.single().title)
        assertEquals(1, result.request.steps.single().stepNumber)
        assertEquals(listOf("https://example.com/photo.jpg"), result.imageUrls)
        assertTrue(!result.aiFallback)
    }
}
