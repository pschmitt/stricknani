package blue.anika.wolle.ui.admin

import blue.anika.wolle.data.api.dto.AdminUserDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanImpersonateTest {
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
}
