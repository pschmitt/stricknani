package blue.anika.wolle.data.uploads

import blue.anika.wolle.data.api.dto.AttachmentDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.repository.removeProjectAttachment
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectAttachmentMutationTest {
    private val json = Json

    @Test
    fun `attachment upload payload preserves picker metadata for replay`() {
        val pending =
            PendingUpload(
                path = "/data/data/blue.anika.wolle/files/pending-uploads/report-1.pdf",
                fileName = "report 1.pdf",
                contentType = "application/pdf",
            )

        val replayed = json.decodeFromString<PendingUpload>(json.encodeToString(pending))

        assertEquals(pending, replayed)
    }

    @Test
    fun `attachment deletion payload preserves server attachment id`() {
        val pending = PendingAttachmentDelete(attachmentId = 42)

        val replayed = json.decodeFromString<PendingAttachmentDelete>(json.encodeToString(pending))

        assertEquals(42, replayed.attachmentId)
    }

    @Test
    fun `optimistic deletion removes only the selected attachment`() {
        val project =
            ProjectDto(
                id = 7,
                name = "Cardigan",
                isAiEnhanced = false,
                isFavorite = false,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                attachments =
                    listOf(
                        attachment(41, "notes.pdf"),
                        attachment(42, "chart.pdf"),
                    ),
            )

        val updated = removeProjectAttachment(project, attachmentId = 41)

        assertEquals(listOf(42), updated.attachments.map { it.id })
        assertFalse(updated.attachments.any { it.originalFilename == "notes.pdf" })
    }

    private fun attachment(id: Int, name: String) =
        AttachmentDto(
            id = id,
            filename = "stored-$id.pdf",
            originalFilename = name,
            contentType = "application/pdf",
            sizeBytes = 123,
            url = "/media/$id",
        )
}
