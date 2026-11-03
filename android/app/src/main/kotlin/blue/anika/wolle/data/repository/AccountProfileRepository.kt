package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Who is signed in, as far as the app knows: [avatarUrl] is absolute and ready for Coil. */
data class AccountProfile(val email: String?, val avatarUrl: String?)

/**
 * The signed-in account's email and profile picture for Settings.
 *
 * `GET /api/v2/account` only accepts a browser session cookie, not the app's Bearer token, so the
 * app cannot ask it. `GET /api/v2/meta` already runs once per app start through [ApiCapabilities]
 * and is the one account lookup a token may make; its `user.avatar_url` (when the server reports
 * one) is remembered in [SettingsRepository] so the picture also shows offline. A v1-only server,
 * an offline start or a server without the field leave the saved values (or none) in place, and the
 * UI falls back to the email's initial.
 */
@Singleton
class AccountProfileRepository
@Inject
constructor(
    private val capabilities: ApiCapabilities,
    private val settings: SettingsRepository,
    private val mediaUrlResolver: MediaUrlResolver,
) {
    val profile: Flow<AccountProfile> =
        combine(settings.accountEmail, settings.accountAvatarUrl, settings.credentials) {
            email,
            avatar,
            _ ->
            AccountProfile(email, mediaUrlResolver.resolve(avatar))
        }

    /** One meta probe at most (cached by [ApiCapabilities]); failures keep what is saved. */
    suspend fun refresh() {
        val user =
            try {
                capabilities.resolve().user
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return
            } ?: return
        if (user.email != settings.accountEmail.value) settings.setAccountEmail(user.email)
        if (user.avatarUrl != settings.accountAvatarUrl.value) {
            settings.setAccountAvatarUrl(user.avatarUrl)
        }
    }
}
