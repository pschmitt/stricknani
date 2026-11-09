package blue.anika.wolle.data.imports

import blue.anika.wolle.data.api.v2.ImportJobDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportDraftsTest {
    private fun draft(id: String, status: String) = ImportJobDto(id = id, status = status)

    @Test
    fun `only finished drafts that nobody saw are announced`() {
        val drafts =
            listOf(
                draft("running", ImportJobDto.STATUS_RUNNING),
                draft("ready", ImportJobDto.STATUS_READY),
                draft("seen", ImportJobDto.STATUS_READY),
                draft("failed", ImportJobDto.STATUS_FAILED),
            )

        assertEquals(
            listOf("ready", "failed"),
            draftsToNotify(drafts, seen = setOf("seen")).map { it.id },
        )
    }
}
