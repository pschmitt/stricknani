package blue.anika.wolle.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blue.anika.wolle.R
import blue.anika.wolle.data.account.AccountDto
import blue.anika.wolle.data.account.AccountGateway
import blue.anika.wolle.data.account.AccountProfileResult
import blue.anika.wolle.data.account.ApiTokenDto
import blue.anika.wolle.data.account.AvatarResult
import blue.anika.wolle.data.account.AvatarSource
import blue.anika.wolle.data.account.CreateTokenResult
import blue.anika.wolle.data.account.CreatedTokenDto
import blue.anika.wolle.data.account.PasswordChangeResult
import blue.anika.wolle.data.account.RevokeResult
import blue.anika.wolle.data.account.TokenListResult
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.ui.common.DangerousActionGuard
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.oidc.DEFAULT_PROVIDER_NAME
import blue.anika.wolle.ui.oidc.ReauthRequest
import blue.anika.wolle.ui.oidc.ReauthUiState
import blue.anika.wolle.ui.oidc.StepUp
import blue.anika.wolle.ui.oidc.showOidcFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.net.URI
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the server offers the bearer account routes; unknown until the first list answered. */
enum class AccountAvailability {
    Unknown,
    Available,
    /** An older server: the password, picture and token features are hidden, not errors. */
    Unsupported,
}

enum class TokenLoad {
    Loading,
    Loaded,
    Failed,
}

/** Why the app is about to sign itself out locally. */
enum class SignOutNotice {
    /** The password changed: the server revoked every session and token. */
    PasswordChanged,
    /** The user revoked the token this app uses. */
    ThisDeviceRevoked,
}

/** Inline form errors as string resources (null: no error). */
data class PasswordFormErrors(
    @StringRes val current: Int? = null,
    @StringRes val new: Int? = null,
    @StringRes val confirm: Int? = null,
    @StringRes val general: Int? = null,
) {
    val any: Boolean
        get() = current != null || new != null || confirm != null || general != null
}

data class TokenFormErrors(
    @StringRes val name: Int? = null,
    @StringRes val password: Int? = null,
    @StringRes val general: Int? = null,
)

const val MIN_PASSWORD_LENGTH = 12
const val MAX_PASSWORD_BYTES = 1024

/** Local checks before a change-password request; null when the input may be sent. */
fun validatePasswordChange(current: String, new: String, confirm: String): PasswordFormErrors? {
    val errors =
        PasswordFormErrors(
            current =
                if (current.isEmpty()) R.string.account_password_err_current_required else null,
            new =
                when {
                    new.length < MIN_PASSWORD_LENGTH -> R.string.account_password_err_new_short
                    new.toByteArray().size > MAX_PASSWORD_BYTES ->
                        R.string.account_password_err_new_long
                    else -> null
                },
            confirm = if (new != confirm) R.string.account_password_err_mismatch else null,
        )
    return errors.takeIf { it.any }
}

data class AccountSecurityState(
    val availability: AccountAvailability = AccountAvailability.Unknown,
    val tokenLoad: TokenLoad = TokenLoad.Loading,
    val tokens: List<ApiTokenDto> = emptyList(),
    /** The token this app signs in with: its row gets the "This device" chip. */
    val currentTokenId: Long? = null,
    val avatarBusy: Boolean = false,
    /** Where the shown avatar comes from; "Remove picture" only applies to an upload. */
    val avatarSource: AvatarSource? = null,
    /** The user's Gravatar preference. */
    val useGravatar: Boolean = true,
    /** The server offers Gravatar (`gravatar_enabled`); false hides the toggle. */
    val gravatarEnabled: Boolean = false,
    val gravatarBusy: Boolean = false,
    /** Remove location and camera data from photos uploaded from now on. */
    val stripPhotoMetadata: Boolean = false,
    val stripPhotoMetadataBusy: Boolean = false,
    val confirmRemoveAvatar: Boolean = false,
    val passwordOpen: Boolean = false,
    val passwordBusy: Boolean = false,
    val passwordErrors: PasswordFormErrors = PasswordFormErrors(),
    val createOpen: Boolean = false,
    val createBusy: Boolean = false,
    val createErrors: TokenFormErrors = TokenFormErrors(),
    /** A just-created token, shown once: dismissing it forgets the secret for good. */
    val revealed: CreatedTokenDto? = null,
    val confirmRevoke: ApiTokenDto? = null,
    val revokingId: Long? = null,
    val signOutNotice: SignOutNotice? = null,
) {
    val available: Boolean
        get() = availability == AccountAvailability.Available
}

/**
 * Settings → Account: profile picture, password, and the token list ("Apps and devices"), for
 * parity with the web Account page. The routes need a server with bearer account access; when the
 * first token list answers 401/404/405 the features simply stay hidden ([AccountAvailability]).
 */
@HiltViewModel
class AccountSecurityViewModel
@Inject
constructor(
    private val gateway: AccountGateway,
    private val capabilities: ApiCapabilities,
    private val feedback: MutationFeedback,
    private val oidc: OidcCoordinator,
    private val guard: DangerousActionGuard,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountSecurityState())
    val state: StateFlow<AccountSecurityState> = _state.asStateFlow()

    /** The name typed for a token whose step-up runs through the login provider. */
    private var pendingTokenName = ""
    private var pendingProviderName = DEFAULT_PROVIDER_NAME

    private val stepUp =
        StepUp(
            oidc,
            viewModelScope,
            onProof = ::createWithProof,
            onFailure = { failure -> feedback.showOidcFailure(failure, pendingProviderName) },
        )

    /** The "confirm it is you" sheet of a provider step-up, or null. */
    val sheet: StateFlow<ReauthUiState?> = stepUp.sheet

    init {
        load()
        viewModelScope.launch { oidc.events.collect { event -> event?.let { stepUp.handle(it) } } }
    }

    fun load() {
        _state.update { it.copy(tokenLoad = TokenLoad.Loading) }
        viewModelScope.launch {
            val currentId =
                try {
                    capabilities.resolve().currentTokenId
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            loadProfile()
            when (val result = gateway.loadTokens()) {
                is TokenListResult.Loaded ->
                    _state.update {
                        it.copy(
                            availability = AccountAvailability.Available,
                            tokenLoad = TokenLoad.Loaded,
                            tokens = result.tokens,
                            currentTokenId = currentId,
                        )
                    }
                TokenListResult.Unsupported ->
                    _state.update {
                        it.copy(
                            availability = AccountAvailability.Unsupported,
                            tokenLoad = TokenLoad.Loaded,
                            tokens = emptyList(),
                        )
                    }
                is TokenListResult.Failed ->
                    _state.update {
                        // Keep what an earlier load showed: a failed refresh is not "unsupported".
                        it.copy(
                            tokenLoad = TokenLoad.Failed,
                            availability =
                                if (it.availability == AccountAvailability.Unknown)
                                    AccountAvailability.Available
                                else it.availability,
                            currentTokenId = currentId ?: it.currentTokenId,
                        )
                    }
            }
        }
    }

    // ---- avatar ----------------------------------------------------------------------------

    private suspend fun loadProfile() {
        val result = gateway.loadProfile()
        if (result is AccountProfileResult.Loaded) applyProfile(result.account)
    }

    private fun applyProfile(account: AccountDto) = _state.update {
        it.copy(
            avatarSource = account.avatarSource,
            useGravatar = account.useGravatar,
            gravatarEnabled = account.gravatarEnabled,
            stripPhotoMetadata = account.stripPhotoMetadata,
        )
    }

    fun setStripPhotoMetadata(enabled: Boolean) {
        if (_state.value.stripPhotoMetadataBusy) return
        _state.update { it.copy(stripPhotoMetadataBusy = true) }
        viewModelScope.launch {
            val result = gateway.setStripPhotoMetadata(enabled)
            _state.update { it.copy(stripPhotoMetadataBusy = false) }
            when (result) {
                is AccountProfileResult.Loaded -> {
                    applyProfile(result.account)
                    feedback.show(
                        if (result.account.stripPhotoMetadata) R.string.account_strip_metadata_on
                        else R.string.account_strip_metadata_off
                    )
                }
                else -> feedback.show(R.string.error_strip_metadata_save_failed)
            }
        }
    }

    fun setUseGravatar(enabled: Boolean) {
        if (_state.value.gravatarBusy || _state.value.avatarBusy) return
        _state.update { it.copy(gravatarBusy = true) }
        viewModelScope.launch {
            val result = gateway.setUseGravatar(enabled)
            when (result) {
                is AccountProfileResult.Loaded -> {
                    applyProfile(result.account)
                    if (enabled && result.account.avatarSource == AvatarSource.UPLOAD) {
                        _state.update { it.copy(avatarBusy = true) }
                        val removal = gateway.deleteAvatar()
                        _state.update { it.copy(avatarBusy = false) }
                        if (removal == AvatarResult.Removed) {
                            // The server now chooses its Gravatar fallback. Refresh the shared
                            // profile repository and security state so every avatar updates.
                            _state.update {
                                it.copy(
                                    avatarSource =
                                        AvatarSource.GRAVATAR.takeIf {
                                            result.account.gravatarEnabled
                                        }
                                )
                            }
                            loadProfile()
                            feedback.show(R.string.account_gravatar_enabled)
                        } else {
                            // Keep the uploaded image visible; the preference is saved, but the
                            // user can retry the switch after the failed delete.
                            feedback.show(R.string.error_gravatar_enabled_avatar_remove_failed)
                        }
                    } else {
                        feedback.show(
                            if (enabled) R.string.account_gravatar_enabled
                            else R.string.account_gravatar_disabled
                        )
                    }
                }
                else -> feedback.show(R.string.error_gravatar_save_failed)
            }
            _state.update { it.copy(gravatarBusy = false, avatarBusy = false) }
        }
    }

    fun uploadAvatar(uri: String) {
        if (_state.value.avatarBusy) return
        _state.update { it.copy(avatarBusy = true) }
        viewModelScope.launch {
            val result =
                try {
                    gateway.uploadAvatar(uri)
                } finally {
                    runCatching {
                        URI(uri).takeIf { it.scheme == "file" }?.let(::File)?.delete()
                    }
                }
            _state.update { it.copy(avatarBusy = false) }
            when (result) {
                is AvatarResult.Uploaded -> {
                    _state.update { it.copy(avatarSource = AvatarSource.UPLOAD) }
                    feedback.show(R.string.account_avatar_updated)
                }
                AvatarResult.TooLarge -> feedback.show(R.string.error_avatar_too_large)
                AvatarResult.UnsupportedImage -> feedback.show(R.string.error_avatar_invalid)
                is AvatarResult.Failed -> feedback.show(R.string.error_avatar_upload_failed)
                AvatarResult.Removed -> Unit
            }
        }
    }

    fun cameraDenied() = feedback.show(R.string.photo_camera_denied)

    fun askRemoveAvatar() = _state.update { it.copy(confirmRemoveAvatar = true) }

    fun dismissRemoveAvatar() = _state.update { it.copy(confirmRemoveAvatar = false) }

    fun confirmRemoveAvatar() {
        _state.update { it.copy(confirmRemoveAvatar = false, avatarBusy = true) }
        viewModelScope.launch {
            val result = gateway.deleteAvatar()
            _state.update { it.copy(avatarBusy = false) }
            // The server may now fall back to the Gravatar.
            if (result == AvatarResult.Removed) loadProfile()
            feedback.show(
                if (result == AvatarResult.Removed) R.string.account_avatar_removed
                else R.string.error_avatar_remove_failed
            )
        }
    }

    // ---- password --------------------------------------------------------------------------

    fun setPasswordOpen(open: Boolean) = _state.update {
        it.copy(passwordOpen = open, passwordErrors = PasswordFormErrors())
    }

    fun changePassword(current: String, new: String, confirm: String) {
        if (_state.value.passwordBusy) return
        val invalid = validatePasswordChange(current, new, confirm)
        if (invalid != null) {
            _state.update { it.copy(passwordErrors = invalid) }
            return
        }
        _state.update { it.copy(passwordBusy = true, passwordErrors = PasswordFormErrors()) }
        viewModelScope.launch {
            val result = gateway.changePassword(current, new)
            _state.update { state ->
                when (result) {
                    // The server revoked every credential, this one included, and minted none:
                    // the app can only sign out (after telling the user why).
                    PasswordChangeResult.Changed ->
                        state.copy(
                            passwordBusy = false,
                            passwordOpen = false,
                            signOutNotice = SignOutNotice.PasswordChanged,
                        )
                    PasswordChangeResult.CurrentIncorrect ->
                        state.copy(
                            passwordBusy = false,
                            passwordErrors =
                                PasswordFormErrors(current = R.string.account_password_err_current),
                        )
                    PasswordChangeResult.NewRejected ->
                        state.copy(
                            passwordBusy = false,
                            passwordErrors =
                                PasswordFormErrors(new = R.string.account_password_err_new_common),
                        )
                    PasswordChangeResult.RateLimited ->
                        state.copy(
                            passwordBusy = false,
                            passwordErrors =
                                PasswordFormErrors(general = R.string.error_account_rate_limited),
                        )
                    is PasswordChangeResult.Failed ->
                        state.copy(
                            passwordBusy = false,
                            passwordErrors =
                                PasswordFormErrors(
                                    general =
                                        if (result.offline) R.string.error_account_offline
                                        else R.string.account_password_err_general
                                ),
                        )
                }
            }
        }
    }

    // ---- tokens ----------------------------------------------------------------------------

    fun setCreateOpen(open: Boolean) = _state.update {
        it.copy(createOpen = open, createErrors = TokenFormErrors())
    }

    /**
     * Accounts without a password (and anyone who prefers it): confirm with the login [provider]
     * instead of the current password. Opens the confirm sheet; the token is created, with the
     * `create_api_token` proof, once the provider answered.
     */
    fun createTokenWithProvider(name: String, provider: OidcProvider) {
        if (_state.value.createBusy) return
        pendingTokenName = name
        pendingProviderName = provider.name
        _state.update { it.copy(createErrors = TokenFormErrors()) }
        stepUp.begin(
            ReauthRequest(
                action = ReauthAction.CREATE_API_TOKEN,
                target = ReauthTarget.None,
                canUsePassword = false,
                oidcProvider = provider,
            )
        )
    }

    fun dismissSheet() = stepUp.dismiss()

    fun submitPassword(password: String) = stepUp.submitPassword(password)

    fun confirmWithProvider() = stepUp.useProvider()

    /** Confirms with one of the account's passkeys (offered by the sheet when it has one). */
    fun confirmWithPasskey(host: blue.anika.wolle.data.passkey.PasskeyHost) =
        stepUp.usePasskey(host)

    private suspend fun createWithProof(proof: ReauthProof) {
        if (_state.value.createBusy) return
        _state.update { it.copy(createBusy = true, createErrors = TokenFormErrors()) }
        applyCreateResult(
            gateway.createTokenWithProof(pendingTokenName, proof),
            viaProvider = true,
        )
    }

    fun createToken(name: String, currentPassword: String) {
        if (_state.value.createBusy) return
        if (currentPassword.isEmpty()) {
            _state.update {
                it.copy(
                    createErrors =
                        TokenFormErrors(password = R.string.account_token_err_password_required)
                )
            }
            return
        }
        _state.update { it.copy(createBusy = true, createErrors = TokenFormErrors()) }
        viewModelScope.launch { applyCreateResult(gateway.createToken(name, currentPassword)) }
    }

    private fun applyCreateResult(result: CreateTokenResult, viaProvider: Boolean = false) {
        _state.update { state ->
            when (result) {
                is CreateTokenResult.Created ->
                    state.copy(
                        createBusy = false,
                        createOpen = false,
                        revealed = result.token,
                        tokens = listOf(result.token.summary()) + state.tokens,
                    )
                CreateTokenResult.WrongPassword ->
                    state.copy(
                        createBusy = false,
                        createErrors =
                            TokenFormErrors(password = R.string.account_password_err_current),
                    )
                CreateTokenResult.ReauthRequired ->
                    state.copy(
                        createBusy = false,
                        createErrors =
                            TokenFormErrors(
                                general =
                                    if (viaProvider) R.string.error_oidc_reauth_required
                                    else R.string.account_token_err_reauth
                            ),
                    )
                CreateTokenResult.NameTooLong ->
                    state.copy(
                        createBusy = false,
                        createErrors = TokenFormErrors(name = R.string.account_token_err_name),
                    )
                CreateTokenResult.RateLimited ->
                    state.copy(
                        createBusy = false,
                        createErrors =
                            TokenFormErrors(general = R.string.error_account_rate_limited),
                    )
                CreateTokenResult.SessionExpired ->
                    state.copy(
                        createBusy = false,
                        createErrors =
                            TokenFormErrors(general = R.string.account_token_err_session),
                    )
                is CreateTokenResult.Failed ->
                    state.copy(
                        createBusy = false,
                        createErrors =
                            TokenFormErrors(
                                general =
                                    if (result.offline) R.string.error_account_offline
                                    else R.string.account_token_err_create
                            ),
                    )
            }
        }
    }

    /** The user saw (and copied) the token: forget the secret. */
    fun dismissRevealed() = _state.update { it.copy(revealed = null) }

    fun askRevoke(token: ApiTokenDto) = _state.update { it.copy(confirmRevoke = token) }

    fun dismissRevoke() = _state.update { it.copy(confirmRevoke = null) }

    fun confirmRevoke() {
        val token = _state.value.confirmRevoke ?: return
        _state.update { it.copy(confirmRevoke = null) }
        guard.confirm(R.string.unlock_reason_revoke_token) { revoke(token) }
    }

    private fun revoke(token: ApiTokenDto) {
        _state.update { it.copy(revokingId = token.id) }
        viewModelScope.launch {
            val result = gateway.revokeToken(token.id)
            _state.update { state ->
                val removed =
                    state.copy(
                        revokingId = null,
                        tokens = state.tokens.filterNot { it.id == token.id },
                    )
                when (result) {
                    // current_token: the server just revoked the token this app uses, so every
                    // further call would be 401. Sign out locally (after a notice).
                    is RevokeResult.Revoked ->
                        if (result.currentToken)
                            removed.copy(signOutNotice = SignOutNotice.ThisDeviceRevoked)
                        else removed
                    RevokeResult.AlreadyGone -> removed
                    is RevokeResult.Failed -> state.copy(revokingId = null)
                }
            }
            when (result) {
                is RevokeResult.Revoked ->
                    if (!result.currentToken) feedback.showRemoved(R.string.account_token_revoked)
                RevokeResult.AlreadyGone -> feedback.showRemoved(R.string.account_token_revoked)
                is RevokeResult.Failed -> feedback.show(R.string.error_token_revoke_failed)
            }
        }
    }

    /** The notice was read: clear it and sign out locally. */
    fun confirmSignOut(signOut: () -> Unit) {
        _state.update { it.copy(signOutNotice = null) }
        signOut()
    }
}
