package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.api.dto.ProjectImportResponseDto
import blue.anika.wolle.data.api.dto.ProjectImportYarnDto
import blue.anika.wolle.data.api.dto.ProjectImportYarnMatchDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ImportedYarn
import blue.anika.wolle.data.repository.toPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectImportReviewTest {
    private fun preview(vararg yarns: ImportedYarn) =
        ImportedProjectPreview(
            sourceUrl = "https://example.test/p",
            request = ProjectWriteRequest(name = "Cardigan"),
            imageUrls = emptyList(),
            aiFallback = false,
            yarns = yarns.toList(),
        )

    private val matched = ImportedYarn(name = "DROPS Air", suggestedStashId = 7)
    private val unmatched =
        ImportedYarn(
            name = "DROPS Merino Extra Fine",
            brand = "Garnstudio",
            amount = "250-300 g",
            weightGrams = 50,
            lengthMeters = 105,
            imageUrl = "https://images.example.test/m.jpg",
            notes = "Garngruppe B",
        )

    @Test
    fun `a suggested stash yarn is preselected and others default to creating`() {
        val review = ProjectImportReview.from(preview(matched, unmatched), notes = "")
        assertEquals(ImportYarnChoice.Stash, review.yarns[0].choice)
        assertEquals(7, review.yarns[0].stashId)
        assertEquals(ImportYarnChoice.New, review.yarns[1].choice)
        assertNull(review.yarns[1].stashId)
    }

    @Test
    fun `stash yarns are linked once and new yarns are created with their details`() {
        var review = ProjectImportReview.from(preview(matched, unmatched), notes = "Designer: X")
        review = review.addExtra(7).addExtra(9).addExtra(9)
        val request = review.applyTo(ProjectWriteRequest(name = "Cardigan"), "Amount")

        assertEquals(listOf(7, 9), request.yarnIds)
        assertEquals(1, request.newYarns.size)
        val created = request.newYarns.single()
        assertEquals("DROPS Merino Extra Fine", created.name)
        assertEquals(50, created.weightGrams)
        assertEquals(105, created.lengthMeters)
        assertEquals(listOf("https://images.example.test/m.jpg"), created.imageUrls)
        assertEquals("Garngruppe B\n\nAmount: 250-300 g", created.notes)
        assertEquals("Designer: X", request.notes)
    }

    @Test
    fun `picking a different stash yarn replaces the suggestion`() {
        val review = ProjectImportReview.from(preview(matched), notes = "").pickStash(0, 12)
        assertEquals(listOf(12), review.linkedStashIds)
    }

    @Test
    fun `skipping adds nothing and stash without a pick links nothing`() {
        val skipped =
            ProjectImportReview.from(preview(matched, unmatched), notes = "")
                .choose(0, ImportYarnChoice.Skip)
                .choose(1, ImportYarnChoice.Skip)
        val none = skipped.applyTo(ProjectWriteRequest(name = "Cardigan"), "Amount")
        assertEquals(emptyList<Int>(), none.yarnIds)
        assertEquals(emptyList<Any>(), none.newYarns)

        val waiting =
            ProjectImportReview.from(preview(unmatched), notes = "")
                .choose(0, ImportYarnChoice.Stash)
        assertEquals(emptyList<Int>(), waiting.linkedStashIds)
        assertEquals(
            emptyList<Any>(),
            waiting.applyTo(ProjectWriteRequest(name = "c"), "Amount").newYarns,
        )
    }

    @Test
    fun `edits to a new yarn and out of range indexes are handled`() {
        val review =
            ProjectImportReview.from(preview(unmatched), notes = "")
                .editYarn(0) { it.copy(name = "DROPS Merino", brand = null) }
                .choose(5, ImportYarnChoice.Skip)
        val created = review.applyTo(ProjectWriteRequest(name = "c"), "Amount").newYarns.single()
        assertEquals("DROPS Merino", created.name)
        assertNull(created.brand)
    }

    @Test
    fun `blank notes are saved as no notes`() {
        val request =
            ProjectImportReview.from(preview(), notes = "  ")
                .applyTo(ProjectWriteRequest(name = "c"), "Amount")
        assertNull(request.notes)
    }

    @Test
    fun `the server's yarns and stash suggestions map into the preview`() {
        val dto =
            ProjectImportResponseDto(
                name = "Cardigan",
                yarns =
                    listOf(
                        ProjectImportYarnDto(
                            name = "DROPS Air",
                            weight = "50 g",
                            length = "150 m",
                            amount = "300 g",
                        ),
                        ProjectImportYarnDto(name = "  "),
                        ProjectImportYarnDto(name = "DROPS Karisma", weight = "about 50 g"),
                    ),
                yarnMatches =
                    listOf(ProjectImportYarnMatchDto(index = 2, yarnId = 4, exact = true)),
            )
        val yarns = dto.toPreview("https://example.test/p").yarns
        assertEquals(2, yarns.size)
        assertEquals(50, yarns[0].weightGrams)
        assertEquals(150, yarns[0].lengthMeters)
        assertEquals("300 g", yarns[0].amount)
        assertNull(yarns[0].suggestedStashId)
        assertEquals(4, yarns[1].suggestedStashId)
        assertNull(yarns[1].weightGrams)
    }
}
