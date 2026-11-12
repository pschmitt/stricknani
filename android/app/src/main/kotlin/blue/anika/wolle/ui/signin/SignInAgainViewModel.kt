package blue.anika.wolle.ui.signin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.device.DeviceNameProvider
import blue.anika.wolle.data.onboarding.OnboardingError
import blue.anika.wolle.data.onboarding.OnboardingValidationException
import blue.anika.wolle.data.onboarding.PasswordTokenMinter
import blue.anika.wolle.data.passkey.AccountIdentityStore
import blue.anika.wolle.data.passkey.PasskeyFailure
import blue.anika.wolle.data.passkey.PasskeyHost
import blue.anika.wolle.data.passkey.PasskeyLoginApi
import blue.anika.wolle.data.passkey.PasskeyService
import blue.anika.wolle.data.passkey.PasskeySignInAgain
import blue.anika.wolle.data.passkey.QueuedWork
import blue.anika.wolle.data.passkey.SignInAgainOutcome
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.passkey.messageResId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SignInAgainState {
    data object Idle : SignInAgainState

    data object Working : SignInAgainState

    data class Error(val message: String) : SignInAgainState

    /** The new token is saved; the screen can close. */
    data object Done : SignInAgainState
}

/**
 * True when signing in again as [typedEmail] may keep the cache and the queued writes: the email
 * remembered from the original sign-in is unknown (a QR setup) or the same account. A different
 * account must go through sign-out, which wipes the queue, so that account A's queued writes are
 * never replayed under account B's token (the OIDC path has the same guard, `AccountMismatch`).
 */
internal fun isSameAccountForSignInAgain(savedEmail: String?, typedEmail: String): Boolean =
    savedEmail.isNullOrBlank() || savedEmail.trim().equals(typedEmail.trim(), ignoreCase = true)

/**
 * Signs in again with email and password against the saved server, for sessions that did not come
 * from the identity provider (password login, QR setup). Only the token is replaced: the local
 * cache and the queued writes stay, and the queue is replayed right after.
 */
@HiltViewModel
class SignInAgainViewModel
@Inject
constructor(
    private val settings: SettingsRepository,
    private val minter: PasswordTokenMinter,
    private val deviceNames: DeviceNameProvider,
    private val syncScheduler: SyncScheduler,
    private val passkeys: PasskeyService,
    private val passkeyLogin: PasskeyLoginApi,
    private val queuedWork: QueuedWork,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<SignInAgainState>(SignInAgainState.Idle)
    val state: StateFlow<SignInAgainState> = _state.asStateFlow()

    val serverUrl: String
        get() = settings.credentials.value.serverUrl

    private val _passkeysAvailable = MutableStateFlow(false)

    /** The saved server advertises passkey sign-in. */
    val passkeysAvailable: StateFlow<Boolean> = _passkeysAvailable.asStateFlow()

    init {
        viewModelScope.launch { _passkeysAvailable.value = passkeys.available(serverUrl) }
    }

    private val passkeyReSignIn =
        PasskeySignInAgain(
            passkeys,
            passkeyLogin,
            object : AccountIdentityStore {
                override val userId
                    get() = settings.accountUserId.value

                override val email
                    get() = settings.accountEmail.value

                override fun remember(userId: Long, email: String) {
                    settings.setAccountUserId(userId)
                    settings.setAccountEmail(email)
                }
            },
            queuedWork,
        )

    /**
     * Signs in again with a passkey. Like the password path, only the token is replaced, so the
     * passkey must be the account the cache and queued writes belong to (see [PasskeySignInAgain]);
     * otherwise its token is dropped. A dismissed system sheet is not an error.
     */
    fun signInWithPasskey(host: PasskeyHost) {
        if (_state.value is SignInAgainState.Working) return
        _state.value = SignInAgainState.Working
        viewModelScope.launch {
            when (val outcome = passkeyReSignIn.run(host, serverUrl, deviceNames.deviceName())) {
                is SignInAgainOutcome.Accepted -> {
                    settings.replaceToken(outcome.token, expiresAt = null, authOrigin = null)
                    syncScheduler.replayThenSyncNow()
                    _state.value = SignInAgainState.Done
                }
                SignInAgainOutcome.Mismatch ->
                    _state.value =
                        SignInAgainState.Error(
                            context.getString(R.string.sign_in_again_account_mismatch)
                        )
                is SignInAgainOutcome.Failed ->
                    _state.value =
                        if (outcome.failure == PasskeyFailure.Cancelled) SignInAgainState.Idle
                        else
                            SignInAgainState.Error(
                                context.getString(outcome.failure.messageResId())
                            )
            }
        }
    }

    /** The email remembered from the password sign-in, if there was one. */
    val savedEmail: String
        get() = settings.accountEmail.value.orEmpty()

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _state.value =
                SignInAgainState.Error(context.getString(R.string.sign_in_again_missing_fields))
            return
        }
        if (_state.value is SignInAgainState.Working) return
        if (!isSameAccountForSignInAgain(settings.accountEmail.value, email)) {
            _state.value =
                SignInAgainState.Error(context.getString(R.string.sign_in_again_account_mismatch))
            return
        }
        _state.value = SignInAgainState.Working
        viewModelScope.launch {
            minter
                .mintToken(
                    serverUrl,
                    email.trim(),
                    password,
                    tokenName = deviceNames.tokenName(),
                    deviceName = deviceNames.deviceName(),
                )
                .onSuccess { token ->
                    // Only the token changes, so the cache and queued writes stay; this also
                    // lifts the "signed out" state.
                    settings.replaceToken(token, expiresAt = null, authOrigin = null)
                    settings.setAccountEmail(email)
                    syncScheduler.replayThenSyncNow()
                    _state.value = SignInAgainState.Done
                }
                .onFailure { _state.value = SignInAgainState.Error(it.toUserMessage()) }
        }
    }

    private fun Throwable.toUserMessage(): String =
        when (this) {
            is OnboardingValidationException ->
                when (val e = error) {
                    OnboardingError.MalformedUrl ->
                        context.getString(R.string.onboarding_error_malformed_url)
                    is OnboardingError.NotAStricknaniServer ->
                        context.getString(R.string.onboarding_error_not_a_server, e.code)
                    OnboardingError.Unauthorized,
                    OnboardingError.InvalidCredentials ->
                        context.getString(R.string.onboarding_error_invalid_credentials)
                    OnboardingError.RateLimited ->
                        context.getString(R.string.onboarding_error_rate_limited)
                    OnboardingError.Unreachable ->
                        context.getString(R.string.onboarding_error_unreachable)
                    is OnboardingError.ServerError ->
                        context.getString(R.string.onboarding_error_server_error, e.code)
                }
            else -> context.getString(R.string.onboarding_error_generic)
        }
}
