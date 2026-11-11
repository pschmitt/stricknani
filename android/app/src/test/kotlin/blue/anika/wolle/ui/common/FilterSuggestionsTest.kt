package blue.anika.wolle.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSuggestionsTest {
    private val labels =
        FixedFilterLabels(
            inProgress = "In progress",
            done = "Done",
            mine = "Mine",
            shared = "Shared with me",
            favorites = "Favorites only",
            trash = "Trash",
        )
    private val tags = listOf("Mütze", "Kinder-Mütze", "socks")
    private val categories = listOf("Mützen", "Schals")
    private val owners = listOf(OwnerBadgeData(7, "Ben", null), OwnerBadgeData(9, "Mia", null))

    private fun labels(
        query: String,
        kind: SuggestionKind = SuggestionKind.Projects,
        isAdmin: Boolean = true,
        owners: List<OwnerBadgeData> = this.owners,
    ) =
        buildFilterSuggestions(query, kind, tags, categories, owners, isAdmin, labels).map {
            "${it.group.name.lowercase()}:${it.label}"
        }

    @Test
    fun `matching ignores case and diacritics`() {
        for (typed in listOf("mü", "mu", "MÜ", "Mu", " mü ")) {
            assertEquals(typed, 0, suggestionMatchRank(foldedSuggestionQuery(typed), "Mütze"))
        }
        assertEquals(0, suggestionMatchRank(foldedSuggestionQuery("mütze"), "mutze"))
    }

    @Test
    fun `a prefix of the value or of a word matches, the middle does not`() {
        assertEquals(1, suggestionMatchRank("mu", "Kinder-Mütze"))
        assertEquals(1, suggestionMatchRank("bl", "Baby blanket"))
        assertNull(suggestionMatchRank("tze", "Mütze"))
        assertNull(suggestionMatchRank("mu", "Wintermütze"))
        assertNull(suggestionMatchRank("", "Mütze"))
    }

    @Test
    fun `groups are tags, categories, status, owners and the rest`() {
        assertEquals(
            listOf("tags:Mütze", "tags:Kinder-Mütze", "categories:Mützen"),
            labels("mü"),
        )
        assertEquals(
            listOf("tags:socks", "categories:Schals", "owners:Shared with me"),
            labels("s"),
        )
        assertEquals(
            listOf(
                "tags:Mütze",
                "tags:Kinder-Mütze",
                "categories:Mützen",
                "owners:Mine",
                "owners:Mia",
                "owners:Shared with me",
            ),
            labels("m"),
        )
    }

    @Test
    fun `the fixed filters match their labels`() {
        assertEquals(listOf("status:Done"), labels("do"))
        assertEquals(listOf("status:In progress"), labels("in"))
        assertEquals(listOf("other:Trash"), labels("tra"))
        assertEquals(listOf("other:Favorites only"), labels("fav"))
    }

    @Test
    fun `yarns have no tags, categories or status`() {
        assertEquals(emptyList<String>(), labels("do", SuggestionKind.Yarns))
        assertEquals(listOf("other:Trash"), labels("tra", SuggestionKind.Yarns))
        assertEquals(emptyList<String>(), labels("mü", SuggestionKind.Yarns, isAdmin = false))
    }

    @Test
    fun `only administrators are offered people`() {
        assertEquals(listOf("owners:Ben"), labels("be", isAdmin = true))
        assertEquals(emptyList<String>(), labels("be", isAdmin = false))
        // Mine and Shared with me name nobody, so they stay for everyone.
        assertTrue("owners:Mine" in labels("m", isAdmin = false))
        assertTrue(labels("m", isAdmin = false).none { it == "owners:Mia" || it == "owners:Ben" })
        val ben =
            buildFilterSuggestions(
                "be",
                SuggestionKind.Projects,
                tags,
                categories,
                owners,
                true,
                labels,
            )
        assertEquals(SuggestionAction.Owner(OwnerChoice.User(7)), ben.single().action)
    }

    @Test
    fun `a blank query offers nothing and a group is capped`() {
        assertEquals(emptyList<String>(), labels("   "))
        val many = (0 until 9).map { "tag $it" }
        val result =
            buildFilterSuggestions(
                "tag",
                SuggestionKind.Projects,
                many,
                emptyList(),
                emptyList(),
                false,
                labels,
            )
        assertEquals(SUGGESTION_GROUP_LIMIT, result.size)
    }

    @Test
    fun `picking sets one filter, never toggles it, and leaves the others`() {
        val start = SuggestedFilters(favoritesOnly = true, tag = "gift")
        assertEquals(
            start.copy(tag = "Mütze"),
            start.applying(SuggestionAction.Tag("Mütze"), SuggestionKind.Projects),
        )
        val again = start.applying(SuggestionAction.Tag("gift"), SuggestionKind.Projects)
        assertEquals("gift", again.tag)
        assertEquals(
            "Mützen",
            start.applying(SuggestionAction.Category("Mützen"), SuggestionKind.Projects).category,
        )
        assertEquals(
            "done",
            start.applying(SuggestionAction.Status("done"), SuggestionKind.Projects).status,
        )
        assertEquals(
            OwnerChoice.Mine,
            start.applying(SuggestionAction.Owner(OwnerChoice.Mine), SuggestionKind.Projects).owner,
        )
        assertTrue(start.applying(SuggestionAction.Trash, SuggestionKind.Projects).trashedOnly)
    }

    @Test
    fun `yarn lists ignore tags, categories and status`() {
        val start = SuggestedFilters()
        assertEquals(start, start.applying(SuggestionAction.Tag("x"), SuggestionKind.Yarns))
        assertEquals(start, start.applying(SuggestionAction.Category("x"), SuggestionKind.Yarns))
        assertEquals(start, start.applying(SuggestionAction.Status("done"), SuggestionKind.Yarns))
        assertTrue(start.applying(SuggestionAction.Favorite, SuggestionKind.Yarns).favoritesOnly)
    }
}
