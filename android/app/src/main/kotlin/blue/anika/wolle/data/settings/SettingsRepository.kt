package blue.anika.wolle.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import blue.anika.wolle.data.oidc.AUTH_ORIGIN_OIDC
import blue.anika.wolle.data.oidc.OidcSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StricknaniCredentials(val serverUrl: String, val apiToken: String) {
    val isValid: Boolean
        get() = serverUrl.isNotBlank() && apiToken.isNotBlank()
}

/** See [SettingsRepository.authInfo]. [expiresAt] is RFC 3339 as sent by the server. */
data class AuthInfo(val expiresAt: String?, val origin: String?) {
    fun isPastExpiry(now: Instant = Instant.now()): Boolean =
        origin == AUTH_ORIGIN_OIDC &&
            expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.isBefore(now) == true
}

/**
 * Server URL + personal access token, backed by [EncryptedSharedPreferences] (Android
 * Keystore-tied, hence `allowBackup=false` in the manifest - a restored backup couldn't decrypt
 * these anyway). Same pattern as the sibling apps (e.g. nyetbox's `SettingsRepository`).
 *
 * Non-secret sync bookkeeping (last-sync timestamps, sync interval preference - SNA-7) is
 * deliberately not here yet: DataStore for that lands with the sync engine that actually reads and
 * writes it, rather than an unused scaffold now.
 */
// AndroidX Security Crypto currently deprecates this API without providing a replacement for the
// same encrypted SharedPreferences migration path. Keep it until the library offers one; the
// suppression makes this intentional compatibility boundary visible to the compiler.
@Suppress("DEPRECATION")
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) :
    OidcSessionStore {

    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            "stricknani_settings",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private val _credentials = MutableStateFlow(loadCredentials())
    val credentials: StateFlow<StricknaniCredentials> = _credentials.asStateFlow()

    /**
     * Email of the account, remembered only when the user signed in with a password (a pasted token
     * or QR code does not say who it belongs to). Used to mark "You" in the admin account list.
     */
    private val _accountEmail = MutableStateFlow(prefs.getString(KEY_ACCOUNT_EMAIL, null))
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    fun setAccountEmail(email: String?) {
        val cleaned = email?.trim()?.ifBlank { null }
        if (cleaned == null) prefs.edit().remove(KEY_ACCOUNT_EMAIL).apply()
        else prefs.edit().putString(KEY_ACCOUNT_EMAIL, cleaned).apply()
        _accountEmail.value = cleaned
    }

    /** Last avatar path the server reported (`/media/...`), so the avatar can show offline. */
    private val _accountAvatarUrl = MutableStateFlow(prefs.getString(KEY_ACCOUNT_AVATAR, null))
    val accountAvatarUrl: StateFlow<String?> = _accountAvatarUrl.asStateFlow()

    fun setAccountAvatarUrl(path: String?) {
        val cleaned = path?.trim()?.ifBlank { null }
        if (cleaned == null) prefs.edit().remove(KEY_ACCOUNT_AVATAR).apply()
        else prefs.edit().putString(KEY_ACCOUNT_AVATAR, cleaned).apply()
        _accountAvatarUrl.value = cleaned
    }

    private val _isConfigured = MutableStateFlow(_credentials.value.isValid)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    /**
     * How the stored token was minted and when it stops working: `auth_origin` is `oidc` for a
     * Stricknani sign-in through the identity provider (those tokens expire, 7 days by default),
     * null for a password or pasted token (no expiry known).
     */
    private val _authInfo =
        MutableStateFlow(
            AuthInfo(
                expiresAt = prefs.getString(KEY_TOKEN_EXPIRES_AT, null),
                origin = prefs.getString(KEY_AUTH_ORIGIN, null),
            )
        )
    val authInfo: StateFlow<AuthInfo> = _authInfo.asStateFlow()

    /**
     * True once an OIDC token is known to be expired or revoked (`401 session_expired`, or its
     * `expires_at` has passed): the app keeps its cache and queued writes and asks the user to sign
     * in again. In memory only; the next 401 sets it again after a restart.
     */
    private val _sessionExpired = MutableStateFlow(_authInfo.value.isPastExpiry())
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * Called by the network layer on a `401 session_expired`. Only an OIDC token that is still the
     * stored one counts: a stale request from before a re-sign-in must not flag the new token.
     */
    fun markSessionExpired(tokenUsed: String?) {
        if (_authInfo.value.origin != AUTH_ORIGIN_OIDC) return
        if (tokenUsed.isNullOrBlank() || tokenUsed != _credentials.value.apiToken) return
        _sessionExpired.value = true
    }

    override val hasSession: Boolean
        get() = _isConfigured.value

    override val sessionServerUrl: String
        get() = _credentials.value.serverUrl

    override val savedAccountEmail: String?
        get() = _accountEmail.value

    override fun saveNewSession(
        serverUrl: String,
        token: String,
        expiresAt: String?,
        authOrigin: String?,
    ) {
        save(serverUrl, token, expiresAt, authOrigin)
    }

    override fun replaceToken(token: String, expiresAt: String?, authOrigin: String?) {
        val trimmed = token.trim()
        prefs.edit().putString(KEY_API_TOKEN, trimmed).putAuthInfo(expiresAt, authOrigin).apply()
        _authInfo.value = AuthInfo(expiresAt, authOrigin)
        _credentials.value = _credentials.value.copy(apiToken = trimmed)
        _sessionExpired.value = false
    }

    /**
     * SNA-15 scheduled backups' optional password - kept here (Keystore-backed) rather than in
     * `AppPreferencesRepository`'s plain DataStore, unlike syncwich's equivalent, since this app
     * already has an encrypted store available and a backup password is exactly the kind of secret
     * it exists for.
     */
    private val _scheduledBackupPassword =
        MutableStateFlow(prefs.getString(KEY_BACKUP_PASSWORD, null))
    val scheduledBackupPassword: StateFlow<String?> = _scheduledBackupPassword.asStateFlow()

    fun setScheduledBackupPassword(password: String?) {
        if (password.isNullOrBlank()) {
            prefs.edit().remove(KEY_BACKUP_PASSWORD).apply()
            _scheduledBackupPassword.value = null
        } else {
            prefs.edit().putString(KEY_BACKUP_PASSWORD, password).apply()
            _scheduledBackupPassword.value = password
        }
    }

    /**
     * Only call once the server has actually confirmed this URL/token pair works - see
     * [blue.anika.wolle.data.onboarding.OnboardingValidator].
     */
    fun save(
        serverUrl: String,
        apiToken: String,
        expiresAt: String? = null,
        authOrigin: String? = null,
    ) {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val trimmedToken = apiToken.trim()
        prefs
            .edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_API_TOKEN, trimmedToken)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_AVATAR)
            .putAuthInfo(expiresAt, authOrigin)
            .apply()
        _accountEmail.value = null
        _accountAvatarUrl.value = null
        _authInfo.value = AuthInfo(expiresAt, authOrigin)
        _sessionExpired.value = false
        _credentials.value = StricknaniCredentials(normalizedUrl, trimmedToken)
        _isConfigured.value = _credentials.value.isValid
    }

    /**
     * Set when the app connected with a web-minted setup token ("Stricknani app (setup)") that
     * still has to be renamed after the device; cleared once that was tried.
     */
    var pendingTokenRename: Boolean
        get() = prefs.getBoolean(KEY_PENDING_TOKEN_RENAME, false)
        set(value) = prefs.edit().putBoolean(KEY_PENDING_TOKEN_RENAME, value).apply()

    /** Wipes credentials. The Room cache (SNA-7) is wiped by the caller alongside this. */
    fun signOut() {
        prefs
            .edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_API_TOKEN)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_AVATAR)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .remove(KEY_AUTH_ORIGIN)
            .remove(KEY_PENDING_TOKEN_RENAME)
            .apply()
        _accountEmail.value = null
        _accountAvatarUrl.value = null
        _authInfo.value = AuthInfo(null, null)
        _sessionExpired.value = false
        _credentials.value = StricknaniCredentials("", "")
        _isConfigured.value = false
    }

    private fun loadCredentials(): StricknaniCredentials =
        StricknaniCredentials(
            serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
            apiToken = prefs.getString(KEY_API_TOKEN, "") ?: "",
        )

    private fun android.content.SharedPreferences.Editor.putAuthInfo(
        expiresAt: String?,
        authOrigin: String?,
    ): android.content.SharedPreferences.Editor = apply {
        if (expiresAt == null) remove(KEY_TOKEN_EXPIRES_AT)
        else putString(KEY_TOKEN_EXPIRES_AT, expiresAt)
        if (authOrigin == null) remove(KEY_AUTH_ORIGIN) else putString(KEY_AUTH_ORIGIN, authOrigin)
    }

    private companion object {
        const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        const val KEY_AUTH_ORIGIN = "auth_origin"
        const val KEY_PENDING_TOKEN_RENAME = "pending_token_rename"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_ACCOUNT_AVATAR = "account_avatar_url"
        const val KEY_BACKUP_PASSWORD = "scheduled_backup_password"
    }
}
