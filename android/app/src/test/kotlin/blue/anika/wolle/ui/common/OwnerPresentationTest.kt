package blue.anika.wolle.ui.common

import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Badge, banner and picker rules for items that belong to somebody else (admin scope). */
class OwnerPresentationTest {
    private fun project(ownerId: Long?, name: String? = null, avatar: String? = null) =
        ProjectEntity(
            id = 1,
            name = "Socks",
            category = null,
            tagsJson = "[]",
            isFavorite = false,
            updatedAt = 0,
            previewUrl = null,
            detailJson = "{}",
            ownerId = ownerId,
            ownerName = name,
            ownerAvatarUrl = avatar,
        )

    private fun yarn(id: Int, ownerId: Long?) =
        YarnEntity(
            id = id,
            name = "Y$id",
            brand = null,
            colorway = null,
            weightCategory = null,
            isFavorite = false,
            updatedAt = 0,
            previewUrl = null,
            detailJson = "{}",
            ownerId = ownerId,
        )

    @Test
    fun `the user's own items carry no badge`() {
        assertNull(project(ownerId = 1, name = "Me").ownerBadge(currentUserId = 1))
    }

    @Test
    fun `items without owner info or without a known user carry no badge`() {
        assertNull(project(ownerId = null).ownerBadge(currentUserId = 1))
        assertNull(project(ownerId = 7, name = "Anika").ownerBadge(currentUserId = null))
    }

    @Test
    fun `someone else's item shows the owner's name and avatar`() {
        val badge = project(7, "Anika", "/api/v2/admin/users/7/avatar/x").ownerBadge(1)

        assertEquals(OwnerBadgeData(7, "Anika", "/api/v2/admin/users/7/avatar/x"), badge)
    }

    @Test
    fun `a blank owner name still marks the item`() {
        assertEquals("?", project(7, " ").ownerBadge(1)?.name)
    }

    @Test
    fun `the banner follows the badge and names the kind`() {
        val badge = project(7, "Anika").ownerBadge(1)

        assertEquals(
            OwnerBannerData("Anika", OwnedKind.Project),
            ownerBannerOf(badge, OwnedKind.Project),
        )
        assertEquals(
            OwnerBannerData("Anika", OwnedKind.Yarn),
            ownerBannerOf(badge, OwnedKind.Yarn),
        )
        assertNull(ownerBannerOf(null, OwnedKind.Project))
        assertNull(ownerBannerOf(project(1, "Me").ownerBadge(1), OwnedKind.Project))
    }

    @Test
    fun `the import stash only offers the importing user's own yarns`() {
        val cached = listOf(yarn(1, ownerId = 1), yarn(2, ownerId = 7), yarn(3, ownerId = null))

        val stash = yarnsOwnedBy(cached, ownerId = null, currentUserId = 1)

        assertEquals(listOf(1, 3), stash.map { it.id })
    }

    @Test
    fun `the editor of someone else's project offers that owner's yarns`() {
        val cached =
            listOf(yarn(1, ownerId = 1), yarn(2, ownerId = 7), yarn(3, ownerId = 7), yarn(4, null))

        val picker = yarnsOwnedBy(cached, ownerId = 7, currentUserId = 1)

        assertEquals(listOf(2, 3), picker.map { it.id })
    }

    @Test
    fun `the editor of one's own project offers one's own yarns`() {
        val cached = listOf(yarn(1, ownerId = 1), yarn(2, ownerId = 7), yarn(4, null))

        assertEquals(listOf(1, 4), yarnsOwnedBy(cached, 1, 1).map { it.id })
    }

    @Test
    fun `on a server without owners every yarn is offered`() {
        val cached = listOf(yarn(1, null), yarn(2, null))

        assertEquals(listOf(1, 2), yarnsOwnedBy(cached, null, null).map { it.id })
    }
}
