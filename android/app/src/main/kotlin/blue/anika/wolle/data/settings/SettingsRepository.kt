package blue.anika.wolle.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StricknaniCredentials(val serverUrl: String, val apiToken: String) {
    val isValid: Boolean
        get() = serverUrl.isNotBlank() && apiToken.isNotBlank()
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
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

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
    fun save(serverUrl: String, apiToken: String) {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val trimmedToken = apiToken.trim()
        prefs
            .edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_API_TOKEN, trimmedToken)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_AVATAR)
            .apply()
        _accountEmail.value = null
        _accountAvatarUrl.value = null
        _credentials.value = StricknaniCredentials(normalizedUrl, trimmedToken)
        _isConfigured.value = _credentials.value.isValid
    }

    /** Wipes credentials. The Room cache (SNA-7) is wiped by the caller alongside this. */
    fun signOut() {
        prefs
            .edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_API_TOKEN)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_AVATAR)
            .apply()
        _accountEmail.value = null
        _accountAvatarUrl.value = null
        _credentials.value = StricknaniCredentials("", "")
        _isConfigured.value = false
    }

    private fun loadCredentials(): StricknaniCredentials =
        StricknaniCredentials(
            serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
            apiToken = prefs.getString(KEY_API_TOKEN, "") ?: "",
        )

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_TOKEN = "api_token"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_ACCOUNT_AVATAR = "account_avatar_url"
        const val KEY_BACKUP_PASSWORD = "scheduled_backup_password"
    }
}
