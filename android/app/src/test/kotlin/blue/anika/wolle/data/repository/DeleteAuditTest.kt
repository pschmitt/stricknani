package blue.anika.wolle.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAuditTest {
    private fun frame(cls: String, method: String, line: Int) =
        StackTraceElement(cls, method, "F.kt", line)

    @Test
    fun `a queued delete names the app frames that queued it`() {
        val stack =
            arrayOf(
                frame("blue.anika.wolle.data.repository.DeleteAudit", "queued", 1),
                frame("blue.anika.wolle.data.repository.ProjectRepository", "deleteProject", 320),
                frame("kotlinx.coroutines.DispatchedTask", "run", 5),
                frame(
                    "blue.anika.wolle.ui.projects.ProjectDetailViewModel\$delete\$1",
                    "invokeSuspend",
                    855,
                ),
            )

        assertEquals(
            "queued delete of project 19 by " +
                "data.repository.ProjectRepository.deleteProject:320 < " +
                "ui.projects.ProjectDetailViewModel\$delete\$1.invokeSuspend:855",
            DeleteAudit.queued("project", 19, stack),
        )
    }

    @Test
    fun `a delete without app frames still says so`() {
        assertTrue(DeleteAudit.queued("project", 1, emptyArray()).contains("no app frame"))
    }

    @Test
    fun `a sent delete says how long it waited`() {
        assertEquals(
            "sending delete of project 19 queued 125s ago",
            DeleteAudit.sending("project", 19, createdAtMillis = 1_000, nowMillis = 126_000),
        )
    }
}
