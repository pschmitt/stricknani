package blue.anika.wolle.data.passkey

/** The account this device's cache and outbox belong to, as far as the app knows. */
interface AccountIdentityStore {
    val userId: Long?
    val email: String?

    fun remember(userId: Long, email: String)
}

/**
 * Whether anything queued on this device (writes, deletes, picked photos) is waiting to be sent.
 */
fun interface QueuedWork {
    suspend fun isPending(): Boolean
}

sealed interface SignInAgainOutcome {
    /** The passkey is the account's own: save [token]. */
    data class Accepted(val token: String) : SignInAgainOutcome

    /**
     * The passkey belongs to another account, or to an unknown one while work is queued: the token
     * is discarded and nothing is saved, so queued writes are never replayed under another account.
     */
    data object Mismatch : SignInAgainOutcome

    data class Failed(val failure: PasskeyFailure) : SignInAgainOutcome
}

/**
 * Decides whether a token minted while signing in again (password or passkey) may replace the saved
 * one. Only the token changes: the cache and the outbox stay, so the account must be the one they
 * belong to. Identity is decided by the server's user id (remembered at the first sign-in); an
 * older session without one falls back to the remembered email. With no identity at all the token
 * is accepted only when nothing is queued. A token that is refused is never saved.
 */
class SignInAgainIdentityCheck(
    private val login: PasskeyLoginApi,
    private val identity: AccountIdentityStore,
    private val queued: QueuedWork,
) {
    /** True when [token] belongs to this device's account; then the identity is remembered. */
    suspend fun accepts(serverUrl: String, token: String): Boolean {
        val who = login.identity(serverUrl, token)
        val knownId = identity.userId
        val knownEmail = identity.email?.trim()?.takeIf { it.isNotEmpty() }
        val sameAccount =
            when {
                knownId != null -> who != null && who.userId == knownId
                knownEmail != null -> who != null && who.email.trim().equals(knownEmail, true)
                else -> !queued.isPending()
            }
        if (!sameAccount) return false
        who?.let { identity.remember(it.userId, it.email) }
        return true
    }
}

/** Signing in again with a passkey: the system sheet, then [SignInAgainIdentityCheck]. */
class PasskeySignInAgain(
    private val passkeys: PasskeyService,
    private val check: SignInAgainIdentityCheck,
) {
    suspend fun run(host: PasskeyHost, serverUrl: String, deviceName: String): SignInAgainOutcome {
        val result = passkeys.signIn(host, serverUrl, deviceName)
        val token =
            result.getOrNull()
                ?: return SignInAgainOutcome.Failed(
                    result.passkeyFailure() ?: PasskeyFailure.Other(null)
                )
        return if (check.accepts(serverUrl, token)) SignInAgainOutcome.Accepted(token)
        else SignInAgainOutcome.Mismatch
    }
}
