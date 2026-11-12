package blue.anika.wolle.ui.common

import blue.anika.wolle.data.api.dto.PersonDto
import org.junit.Assert.assertEquals
import org.junit.Test

class UserSentenceTest {
    private val anna = UserRef(UserKind.User, "Anna", "/a")
    private val bea = UserRef(UserKind.User, "Bea")

    @Test
    fun `a sentence splits into text and the people at their placeholders`() {
        val formatted = "Owner: ${userPlaceholder(0)}"
        assertEquals(
            listOf(SentencePart.Text("Owner: "), SentencePart.Person(anna)),
            sentenceParts(formatted, listOf(anna)),
        )
    }

    @Test
    fun `a translation may reorder the people`() {
        // German word order: the second person comes first.
        val formatted = "${userPlaceholder(1)} handelt als ${userPlaceholder(0)}."
        assertEquals(
            listOf(
                SentencePart.Person(bea),
                SentencePart.Text(" handelt als "),
                SentencePart.Person(anna),
                SentencePart.Text("."),
            ),
            sentenceParts(formatted, listOf(anna, bea)),
        )
    }

    @Test
    fun `a placeholder without a person is dropped and plain text stays whole`() {
        assertEquals(
            listOf(SentencePart.Text("x "), SentencePart.Text(" y")),
            sentenceParts("x ${userPlaceholder(3)} y", listOf(anna)),
        )
        assertEquals(
            listOf(SentencePart.Text("No people")),
            sentenceParts("No people", emptyList()),
        )
    }

    @Test
    fun `owners, shares and accounts become people without an email`() {
        assertEquals(
            UserRef(UserKind.User, "Anna", "/a"),
            ownerRef(OwnerBadgeData(4, "Anna", "/a")),
        )
        val person = PersonDto(4, "Anna", "/a")
        assertEquals(UserKind.User, personRef(person).kind)
        assertEquals(UserKind.User, personRef(person, viewerId = 5).kind)
        assertEquals(UserKind.Self, personRef(person, viewerId = 4).kind)
        assertEquals(
            UserRef(UserKind.User, "Anika Bergmann", "/b"),
            accountRef("anika.bergmann@example.invalid", avatarUrl = "/b"),
        )
        assertEquals("Xandra", accountRef("x@example.invalid", name = " Xandra ").name)
    }

    @Test
    fun `an impersonating administrator keeps their picture, or is generic`() {
        assertEquals(UserRef(UserKind.User, "Root", "/r"), impersonatorRef("Root", "/r"))
        assertEquals(UserKind.Admin, impersonatorRef(null).kind)
    }
}
