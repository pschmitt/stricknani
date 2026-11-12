package blue.anika.wolle.ui.admin

import blue.anika.wolle.data.api.dto.AdminUserDto

/**
 * Whether the web offers "Impersonate" for a user: not yourself, not an administrator, not a
 * disabled account (`canImpersonate` in `AdminUsers.svelte`). The server enforces the same.
 */
fun canImpersonate(user: AdminUserDto, isYou: Boolean): Boolean =
    !isYou && user.active && !user.isAdmin
