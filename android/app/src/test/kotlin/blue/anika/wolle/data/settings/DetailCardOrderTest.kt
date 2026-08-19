package blue.anika.wolle.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailCardOrderTest {

    @Test
    fun `missing and unknown saved cards are sanitized in default order`() {
        assertEquals(
            listOf(ProjectDetailCard.NOTES, ProjectDetailCard.ATTACHMENTS, ProjectDetailCard.DETAILS,
                ProjectDetailCard.STITCH_SAMPLE, ProjectDetailCard.LINKED_YARNS,
                ProjectDetailCard.DESCRIPTION, ProjectDetailCard.STEPS),
            DetailCardOrder.sanitize(
                DetailCardDomain.PROJECT,
                listOf(ProjectDetailCard.NOTES, "unknown", ProjectDetailCard.NOTES, ProjectDetailCard.ATTACHMENTS),
            ),
        )
    }

    @Test
    fun `visible order changes do not disturb optional cards that are absent`() {
        assertEquals(
            listOf(
                ProjectDetailCard.STEPS,
                ProjectDetailCard.ATTACHMENTS,
                ProjectDetailCard.DETAILS,
                ProjectDetailCard.STITCH_SAMPLE,
                ProjectDetailCard.LINKED_YARNS,
                ProjectDetailCard.DESCRIPTION,
                ProjectDetailCard.NOTES,
            ),
            DetailCardOrder.withVisibleOrder(
                DetailCardDomain.PROJECT,
                null,
                listOf(ProjectDetailCard.STEPS, ProjectDetailCard.ATTACHMENTS),
            ),
        )
    }

    @Test
    fun `visible cards follow saved order and append newly available cards`() {
        assertEquals(
            listOf(YarnDetailCard.NOTES, YarnDetailCard.DETAILS, YarnDetailCard.USED_IN),
            DetailCardOrder.visible(
                DetailCardDomain.YARN,
                listOf(YarnDetailCard.NOTES, YarnDetailCard.DESCRIPTION, YarnDetailCard.DETAILS),
                listOf(YarnDetailCard.DETAILS, YarnDetailCard.USED_IN, YarnDetailCard.NOTES),
            ),
        )
    }

    @Test
    fun `move returns a new order without changing unrelated entries`() {
        assertEquals(
            listOf("notes", "details", "description"),
            DetailCardOrder.move(listOf("details", "notes", "description"), 1, 0),
        )
        assertEquals(
            listOf("details", "notes", "description"),
            DetailCardOrder.move(listOf("details", "notes", "description"), -1, 0),
        )
    }
}
