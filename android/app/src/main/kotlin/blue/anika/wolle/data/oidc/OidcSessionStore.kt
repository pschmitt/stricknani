package blue.anika.wolle.data.oidc

/**
 * The slice of the stored sign-in the OIDC flows need.
 * [blue.anika.wolle.data.settings.SettingsRepository] implements it; tests use a fake.
 */
interface OidcSessionStore {
    val hasSession: Boolean
    val sessionServerUrl: String
    val savedAccountEmail: String?

    /** A brand new sign-in (onboarding): replaces nothing, so it clears per-account bookkeeping. */
    fun saveNewSession(serverUrl: String, token: String, expiresAt: String?, authOrigin: String?)

    /**
     * Re-sign-in for the account already on this device: swaps only the token and its expiry, and
     * clears the expired flag. Cached data and queued writes are left alone.
     */
    fun replaceToken(token: String, expiresAt: String?, authOrigin: String?)
}
