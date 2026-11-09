package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.api.dto.ImportAssetDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import blue.anika.wolle.data.repository.ImportedProjectPreview
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectEditorImportTest {
    private val preview =
        ImportedProjectPreview(
            sourceUrl = "https://e.com/p",
            request =
                ProjectWriteRequest(
                    name = "Cardigan",
                    needles = "4 mm",
                    link = "https://e.com/p",
                    imageUrls = listOf("https://e.com/a.jpg"),
                    steps =
                        listOf(
                            StepWriteRequest(
                                title = "Cast on",
                                description = "80 sts",
                                imageUrls = listOf("https://e.com/s.jpg"),
                            )
                        ),
                ),
            imageUrls = listOf("https://e.com/a.jpg", "https://e.com/b.jpg"),
            assets = listOf(ImportAssetDto(url = "https://e.com/chart.jpg")),
            aiFallback = false,
        )

    @Test
    fun `an import fills the editor's own fields`() {
        val form =
            ProjectEditorFormState(description = "mine", notes = "my note")
                .withImport(preview, "Designer: Ann")

        assertEquals("Cardigan", form.name)
        assertEquals("4 mm", form.needles)
        assertEquals("https://e.com/p", form.link)
        // What the import has nothing for stays; notes are added below the user's own.
        assertEquals("mine", form.description)
        assertEquals("my note\nDesigner: Ann", form.notes)
        assertEquals(listOf("Cast on"), form.steps.map { it.title })
        assertEquals(listOf("https://e.com/s.jpg"), form.steps.single().importImageUrls)
        assertEquals(preview.imageUrls, form.importImageUrls)
        assertEquals(setOf("https://e.com/a.jpg"), form.selectedImportImages)
        assertEquals(setOf("https://e.com/chart.jpg"), form.selectedImportAssets)
    }
}
