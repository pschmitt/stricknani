package blue.anika.wolle.ui.admin

import blue.anika.wolle.data.api.dto.AdminUserDto

/**
 * The server lists accounts 50 to a page (`/api/v2/admin/users`), the web page is derived from it.
 */
const val ADMIN_USERS_PAGE_SIZE = 50

/**
 * Whether the web offers "Impersonate" for a user: not yourself, not an administrator, not a
 * disabled account (`canImpersonate` in `AdminUsers.svelte`).
 */
fun canImpersonate(user: AdminUserDto, isYou: Boolean): Boolean =
    !isYou && user.active && !user.isAdmin

/**
 * The web page that opens the "Impersonate this account?" confirmation for [userId]:
 * `/app/admin/users?impersonate=<id>`, on the page of the list that holds the account ([index] is
 * its position in the full list). Impersonating happens there, in the browser, so the app's own
 * session never becomes another account. Null when the app has no server.
 */
fun impersonationUrl(
    serverUrl: String,
    userId: Long,
    index: Int,
    pageSize: Int = ADMIN_USERS_PAGE_SIZE,
): String? {
    val base = serverUrl.trim().trimEnd('/')
    if (base.isEmpty() || index < 0) return null
    val page = index / pageSize + 1
    val pageParam = if (page > 1) "&page=$page" else ""
    return "$base/app/admin/users?impersonate=$userId$pageParam"
}
