package blue.anika.wolle.data.repository

import blue.anika.wolle.ui.common.ItemBanner
import blue.anika.wolle.ui.common.OwnedKind
import blue.anika.wolle.ui.common.OwnerBadgeData
import blue.anika.wolle.ui.common.itemBannerOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each kind of reader sees on a project or yarn: the detail screens enable Edit, section and
 * note shortcuts, media actions and the editor from `canEdit`; Share, Transfer and Delete from
 * `canManage`; Leave from `canLeave`. Favourites are personal and always allowed.
 */
class ItemAccessTest {
    private val viewer = itemAccessOf("viewer", derived = false, canEdit = false, canManage = false)
    private val derivedViewer =
        itemAccessOf("viewer", derived = true, canEdit = false, canManage = false)
    private val editor = itemAccessOf("editor", derived = false, canEdit = true, canManage = false)
    private val owner = itemAccessOf("owner", derived = false, canEdit = true, canManage = true)
    private val admin = itemAccessOf("admin", derived = false, canEdit = true, canManage = true)

    @Test
    fun `a viewer reads only - no editor, note invitation, media, share or delete`() {
        assertFalse(viewer.canEdit)
        assertFalse(viewer.canManage)
        assertTrue(viewer.canLeave)
    }

    @Test
    fun `a derived yarn viewer reads only and has nothing to leave`() {
        assertFalse(derivedViewer.canEdit)
        assertFalse(derivedViewer.canManage)
        assertFalse(derivedViewer.canLeave)
        assertTrue(derivedViewer.isShared)
    }

    @Test
    fun `an editor edits and changes media but never shares, transfers or deletes`() {
        assertTrue(editor.canEdit)
        assertFalse(editor.canManage)
        assertTrue(editor.canLeave)
    }

    @Test
    fun `owner and administrator manage, and neither can leave`() {
        for (a in listOf(owner, admin)) {
            assertTrue(a.canEdit)
            assertTrue(a.canManage)
            assertFalse(a.canLeave)
        }
        assertEquals(ItemAccess.Own, itemAccessOf(null, false, null, null))
    }

    @Test
    fun `a role without flags falls back to what the role allows, never more`() {
        assertFalse(itemAccessOf("viewer", false, null, null).canEdit)
        assertTrue(itemAccessOf("editor", false, null, null).canEdit)
        assertFalse(itemAccessOf("editor", false, null, null).canManage)
        assertTrue(itemAccessOf("admin", false, null, null).canManage)
    }

    @Test
    fun `server flags win over the role string`() {
        assertFalse(itemAccessOf("editor", false, canEdit = false, canManage = false).canEdit)
    }

    @Test
    fun `banner - derived, shared with role, admin, own`() {
        val anna = OwnerBadgeData(7, "Anna", null)

        assertEquals(ItemBanner.Derived, itemBannerOf(anna, derivedViewer, OwnedKind.Yarn))
        assertEquals(
            ItemBanner.SharedBy(anna, "viewer"),
            itemBannerOf(anna, viewer, OwnedKind.Project),
        )
        assertEquals(
            ItemBanner.SharedBy(anna, "editor"),
            itemBannerOf(anna, editor, OwnedKind.Project),
        )
        assertTrue(itemBannerOf(anna, admin, OwnedKind.Project) is ItemBanner.Owned)
        assertNull(itemBannerOf(null, owner, OwnedKind.Project))
    }
}
