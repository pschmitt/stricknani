package blue.anika.wolle.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailCardOrderTest {

    @Test
    fun `missing and unknown saved cards are sanitized in default order`() {
        assertEquals(
            listOf(
                ProjectDetailCard.STEPS,
                ProjectDetailCard.DETAILS,
                ProjectDetailCard.MATERIALS,
                ProjectDetailCard.DESCRIPTION,
            ),
            DetailCardOrder.sanitize(
                DetailCardDomain.PROJECT,
                listOf(
                    ProjectDetailCard.STEPS,
                    "unknown",
                    "source",
                    "attachments",
                    ProjectDetailCard.STEPS,
                ),
            ),
        )
    }

    @Test
    fun `visible order changes do not disturb optional cards that are absent`() {
        assertEquals(
            listOf(
                ProjectDetailCard.STEPS,
                ProjectDetailCard.DETAILS,
                ProjectDetailCard.MATERIALS,
                ProjectDetailCard.DESCRIPTION,
            ),
            DetailCardOrder.withVisibleOrder(
                DetailCardDomain.PROJECT,
                null,
                listOf(ProjectDetailCard.STEPS, ProjectDetailCard.DETAILS),
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
    fun `retired source card is dropped from older saved yarn orders`() {
        assertEquals(
            listOf(
                YarnDetailCard.NOTES,
                YarnDetailCard.DETAILS,
                YarnDetailCard.DESCRIPTION,
                YarnDetailCard.USED_IN,
            ),
            DetailCardOrder.sanitize(
                DetailCardDomain.YARN,
                listOf(YarnDetailCard.NOTES, "source", YarnDetailCard.DETAILS),
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
