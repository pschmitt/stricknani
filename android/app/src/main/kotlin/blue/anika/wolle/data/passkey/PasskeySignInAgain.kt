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
 * Signing in again with a passkey after the saved token was rejected. Only the token may change:
 * the cache and the outbox stay, so the account must be the one they belong to. Identity is decided
 * by the server's user id (remembered at the first sign-in); an older session without one falls
 * back to the remembered email. With no identity at all the passkey is accepted only when nothing
 * is queued.
 */
class PasskeySignInAgain(
    private val passkeys: PasskeyService,
    private val login: PasskeyLoginApi,
    private val identity: AccountIdentityStore,
    private val queued: QueuedWork,
) {
    suspend fun run(host: PasskeyHost, serverUrl: String, deviceName: String): SignInAgainOutcome {
        val result = passkeys.signIn(host, serverUrl, deviceName)
        val token =
            result.getOrNull()
                ?: return SignInAgainOutcome.Failed(
                    result.passkeyFailure() ?: PasskeyFailure.Other(null)
                )
        val who = login.identity(serverUrl, token)
        val knownId = identity.userId
        val knownEmail = identity.email?.trim()?.takeIf { it.isNotEmpty() }
        val sameAccount =
            when {
                knownId != null -> who != null && who.userId == knownId
                knownEmail != null -> who != null && who.email.trim().equals(knownEmail, true)
                else -> !queued.isPending()
            }
        if (!sameAccount) return SignInAgainOutcome.Mismatch
        who?.let { identity.remember(it.userId, it.email) }
        return SignInAgainOutcome.Accepted(token)
    }
}
