package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerFilterTest {
    private val me = 5L

    @Test
    fun `navigation arguments and urls parse to a choice, anything else to none`() {
        assertEquals(OwnerChoice.Mine, ownerChoiceFromArg("me"))
        assertEquals(OwnerChoice.Mine, ownerChoiceFromArg("mine"))
        assertEquals(OwnerChoice.Shared, ownerChoiceFromArg("shared"))
        assertEquals(OwnerChoice.User(42), ownerChoiceFromArg("42"))
        for (bad in listOf(null, "", "0", "-4", "4.5", "abc", "all", "99999999999999999999")) {
            assertNull(bad, ownerChoiceFromArg(bad))
        }
    }

    @Test
    fun `no choice shows everything`() {
        assertTrue(ownerChoiceMatches(null, 7, me))
        assertTrue(ownerChoiceMatches(null, null, me))
    }

    @Test
    fun `mine is the own items, rows without owner info and everything while the user is unknown`() {
        assertTrue(ownerChoiceMatches(OwnerChoice.Mine, null, me))
        assertTrue(ownerChoiceMatches(OwnerChoice.Mine, me, me))
        assertTrue(ownerChoiceMatches(OwnerChoice.Mine, 7, null))
        assertEquals(false, ownerChoiceMatches(OwnerChoice.Mine, 7, me))
    }

    @Test
    fun `shared is everybody else's items`() {
        assertTrue(ownerChoiceMatches(OwnerChoice.Shared, 7, me))
        assertEquals(false, ownerChoiceMatches(OwnerChoice.Shared, null, me))
        assertEquals(false, ownerChoiceMatches(OwnerChoice.Shared, me, me))
    }

    @Test
    fun `one owner matches only that owner, and the user's own id means the own items`() {
        assertTrue(ownerChoiceMatches(OwnerChoice.User(7), 7, me))
        assertEquals(false, ownerChoiceMatches(OwnerChoice.User(7), 8, me))
        assertEquals(false, ownerChoiceMatches(OwnerChoice.User(7), null, me))
        assertTrue(ownerChoiceMatches(OwnerChoice.User(me), null, me))
        assertEquals(false, ownerChoiceMatches(OwnerChoice.User(me), 7, me))
    }

    @Test
    fun `owner options list each other owner once, by name, without the user's own items`() {
        val options =
            ownerOptions(
                sequenceOf(
                    ownerBadgeOf(7, "ben", null, me),
                    ownerBadgeOf(null, null, null, me),
                    ownerBadgeOf(me, "Me", null, me),
                    ownerBadgeOf(8, "Anika", null, me),
                    ownerBadgeOf(7, "ben", null, me),
                )
            )
        assertEquals(listOf("Anika", "ben"), options.map { it.name })
        assertEquals(listOf(8L, 7L), options.map { it.ownerId })
    }
}
