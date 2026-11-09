package blue.anika.wolle.ui.admin

import blue.anika.wolle.data.api.dto.AdminUserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpersonationLinkTest {
    private fun user(active: Boolean = true, admin: Boolean = false) =
        AdminUserDto(id = 7, email = "a@example.invalid", active = active, isAdmin = admin)

    @Test
    fun `member shown like the web`() {
        assertTrue(canImpersonate(user(), isYou = false))
    }

    @Test
    fun `hidden for yourself, administrators and disabled accounts`() {
        assertFalse(canImpersonate(user(), isYou = true))
        assertFalse(canImpersonate(user(admin = true), isYou = false))
        assertFalse(canImpersonate(user(active = false), isYou = false))
    }

    @Test
    fun `link opens the admin users page of the web app`() {
        assertEquals(
            "https://wolle.example.invalid/app/admin/users?impersonate=7",
            impersonationUrl("https://wolle.example.invalid", 7, index = 0),
        )
        assertEquals(
            "https://wolle.example.invalid/app/admin/users?impersonate=7",
            impersonationUrl("https://wolle.example.invalid/", 7, index = 49),
        )
    }

    @Test
    fun `link carries the page of the list that holds the account`() {
        assertEquals(
            "https://h.example.invalid/app/admin/users?impersonate=9&page=2",
            impersonationUrl("https://h.example.invalid", 9, index = 50),
        )
        assertEquals(
            "https://h.example.invalid/app/admin/users?impersonate=9&page=3",
            impersonationUrl("https://h.example.invalid", 9, index = 120),
        )
    }

    @Test
    fun `no link without a server or a position`() {
        assertNull(impersonationUrl("", 7, index = 0))
        assertNull(impersonationUrl("https://h.example.invalid", 7, index = -1))
    }
}
