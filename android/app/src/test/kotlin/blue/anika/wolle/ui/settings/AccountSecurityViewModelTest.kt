package blue.anika.wolle.ui.settings

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
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.api.v2.MetaV2UserDto
import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.data.oidc.FakeAccountApi
import blue.anika.wolle.data.oidc.FakeLoginApi
import blue.anika.wolle.data.oidc.FakeSession
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.OidcProvider
import blue.anika.wolle.data.oidc.PendingOidcFlow
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.ui.common.MutationFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSecurityViewModelTest {
    private class FakeGateway : AccountGateway {
        var list: TokenListResult = TokenListResult.Loaded(emptyList())
        var create: CreateTokenResult = CreateTokenResult.Failed(false)
        var revoke: RevokeResult = RevokeResult.Revoked(false)
        var password: PasswordChangeResult = PasswordChangeResult.Changed
        var avatar: AvatarResult = AvatarResult.Uploaded("/p")
        var profile: AccountProfileResult =
            AccountProfileResult.Loaded(AccountDto(1, "me@example.org"))
        var toggle: AccountProfileResult? = null
        val toggled = mutableListOf<Boolean>()
        var passwordCalls = 0
        var createCalls = 0

        override suspend fun loadProfile() = profile

        override suspend fun setUseGravatar(enabled: Boolean): AccountProfileResult {
            toggled += enabled
            return toggle ?: AccountProfileResult.Failed(offline = false)
        }

        var stripResult: AccountProfileResult? = null
        val stripped = mutableListOf<Boolean>()

        override suspend fun setStripPhotoMetadata(enabled: Boolean): AccountProfileResult {
            stripped += enabled
            return stripResult ?: AccountProfileResult.Failed(offline = false)
        }

        override suspend fun loadTokens() = list

        override suspend fun createToken(name: String, currentPassword: String): CreateTokenResult {
            createCalls++
            return create
        }

        var createWithProof: CreateTokenResult = CreateTokenResult.Failed(false)
        var proofCalls = 0
        var proofName: String? = null
        var proof: ReauthProof? = null

        override suspend fun createTokenWithProof(
            name: String,
            proof: ReauthProof,
        ): CreateTokenResult {
            proofCalls++
            proofName = name
            this.proof = proof
            return createWithProof
        }

        override suspend fun revokeToken(id: Long) = revoke

        override suspend fun changePassword(
            currentPassword: String,
            newPassword: String,
        ): PasswordChangeResult {
            passwordCalls++
            return password
        }

        override suspend fun uploadAvatar(uri: String) = avatar

        override suspend fun deleteAvatar() = AvatarResult.Removed
    }

    private val gateway = FakeGateway()
    private val feedback = MutationFeedback()
    private var currentTokenId: Long? = 7

    private val tablet = ApiTokenDto(3, "tablet", "2026-11-03T10:00:00Z")
    private val thisPhone = ApiTokenDto(7, "phone", "2026-11-01T10:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): AccountSecurityViewModel {
        val meta =
            object : MetaV2Api {
                override suspend fun getMeta() =
                    V2Envelope(
                        MetaV2Dto(
                            user = MetaV2UserDto(1, "me@example.org"),
                            currentTokenId = currentTokenId,
                        )
                    )
            }
        return AccountSecurityViewModel(
            gateway,
            ApiCapabilities(meta) { "https://s\nt" },
            feedback,
            coordinator,
        )
    }

    private val session = FakeSession(hasSession = true, sessionServerUrl = "https://s")
    private val coordinator =
        OidcCoordinator(
            FakeLoginApi(),
            FakeAccountApi(),
            PendingOidcFlow(),
            session,
            CoroutineScope(UnconfinedTestDispatcher()),
            afterSignIn = {},
            tokenName = { "Android test" },
            newState = { "state-1" },
            newVerifier = { "v".repeat(64) },
        )
    private val authelia = OidcProvider("authelia", "Authelia")

    @Test
    fun `an account without a password confirms the token through its provider`() {
        val created = CreatedTokenDto(21, "tablet", "2026-11-03T11:00:00Z", null, "sk_oidc")
        gateway.create = CreateTokenResult.Failed(false)
        gateway.createWithProof = CreateTokenResult.Created(created)
        val vm = viewModel()
        vm.setCreateOpen(true)

        vm.createTokenWithProvider("tablet", authelia)

        val request = vm.sheet.value!!.request
        assertEquals(ReauthAction.CREATE_API_TOKEN, request.action)
        assertEquals(ReauthTarget.None, request.target)
        assertFalse(request.canUsePassword)
        assertEquals(authelia, request.oidcProvider)
        // Nothing is sent before the provider answered, and never with a password.
        assertEquals(0, gateway.createCalls)
        assertEquals(0, gateway.proofCalls)

        vm.confirmWithProvider()
        assertNull(vm.sheet.value)
        assertEquals(0, gateway.proofCalls)

        assertEquals(
            OidcCoordinator.CallbackResult.Accepted,
            coordinator.onCallback("stricknani://oidc-callback?code=c&state=state-1&flow=reauth"),
        )

        assertEquals(1, gateway.proofCalls)
        assertEquals("tablet", gateway.proofName)
        assertEquals(ReauthAction.CREATE_API_TOKEN, gateway.proof?.action)
        assertEquals("reauth-oidc", gateway.proof?.token)
        assertEquals(0, gateway.createCalls)
        val state = vm.state.value
        assertEquals("sk_oidc", state.revealed?.token)
        assertFalse(state.createOpen)
        assertFalse(state.createBusy)
    }

    @Test
    fun `a refused provider proof shows the confirm again message`() {
        gateway.createWithProof = CreateTokenResult.ReauthRequired
        val vm = viewModel()
        vm.setCreateOpen(true)
        vm.createTokenWithProvider("tablet", authelia)
        vm.confirmWithProvider()
        coordinator.onCallback("stricknani://oidc-callback?code=c&state=state-1&flow=reauth")

        val state = vm.state.value
        assertEquals(R.string.error_oidc_reauth_required, state.createErrors.general)
        assertTrue(state.createOpen)
        assertFalse(state.createBusy)
        assertNull(state.revealed)
    }

    @Test
    fun `404 gating hides the features without an error`() {
        gateway.list = TokenListResult.Unsupported
        val state = viewModel().state.value

        assertEquals(AccountAvailability.Unsupported, state.availability)
        assertFalse(state.available)
        assertTrue(state.tokens.isEmpty())
        assertEquals(TokenLoad.Loaded, state.tokenLoad)
    }

    @Test
    fun `a failed first load keeps the features and offers retry`() {
        gateway.list = TokenListResult.Failed(offline = true)
        val vm = viewModel()
        assertEquals(TokenLoad.Failed, vm.state.value.tokenLoad)
        assertTrue(vm.state.value.available)

        gateway.list = TokenListResult.Loaded(listOf(tablet))
        vm.load()
        assertEquals(TokenLoad.Loaded, vm.state.value.tokenLoad)
        assertEquals(listOf(tablet), vm.state.value.tokens)
    }

    @Test
    fun `the list marks which token is this device from meta`() {
        gateway.list = TokenListResult.Loaded(listOf(tablet, thisPhone))
        val state = viewModel().state.value

        assertEquals(7L, state.currentTokenId)
        assertEquals(listOf(false, true), state.tokens.map { it.id == state.currentTokenId })
    }

    @Test
    fun `without current_token_id nothing is marked as this device`() {
        currentTokenId = null
        gateway.list = TokenListResult.Loaded(listOf(tablet, thisPhone))
        val state = viewModel().state.value

        assertNull(state.currentTokenId)
        assertTrue(state.tokens.none { it.id == state.currentTokenId })
    }

    @Test
    fun `a created token is revealed once and then forgotten`() {
        gateway.list = TokenListResult.Loaded(listOf(tablet))
        val created = CreatedTokenDto(20, "watch", "2026-11-03T11:00:00Z", null, "sk_once")
        gateway.create = CreateTokenResult.Created(created)
        val vm = viewModel()
        vm.setCreateOpen(true)

        vm.createToken("watch", "my-password")

        val state = vm.state.value
        assertEquals("sk_once", state.revealed?.token)
        assertFalse(state.createOpen)
        assertEquals(listOf(20L, 3L), state.tokens.map { it.id })
        // The list row never carries the secret.
        assertFalse(state.tokens.first().toString().contains("sk_once"))

        vm.dismissRevealed()
        assertNull(vm.state.value.revealed)
        assertEquals(listOf(20L, 3L), vm.state.value.tokens.map { it.id })
    }

    @Test
    fun `creating needs the current password before any request`() {
        val vm = viewModel()
        vm.createToken("watch", "")

        assertEquals(0, gateway.createCalls)
        assertEquals(
            R.string.account_token_err_password_required,
            vm.state.value.createErrors.password,
        )
    }

    @Test
    fun `create errors land on the right field`() {
        val vm = viewModel()
        gateway.create = CreateTokenResult.WrongPassword
        vm.createToken("w", "pw")
        assertEquals(R.string.account_password_err_current, vm.state.value.createErrors.password)

        gateway.create = CreateTokenResult.ReauthRequired
        vm.createToken("w", "pw")
        assertEquals(R.string.account_token_err_reauth, vm.state.value.createErrors.general)

        gateway.create = CreateTokenResult.NameTooLong
        vm.createToken("w", "pw")
        assertEquals(R.string.account_token_err_name, vm.state.value.createErrors.name)
        assertNull(vm.state.value.revealed)
    }

    @Test
    fun `revoking another token keeps the session`() {
        gateway.list = TokenListResult.Loaded(listOf(tablet, thisPhone))
        gateway.revoke = RevokeResult.Revoked(currentToken = false)
        val vm = viewModel()

        vm.askRevoke(tablet)
        vm.confirmRevoke()

        val state = vm.state.value
        assertNull(state.signOutNotice)
        assertNull(state.confirmRevoke)
        assertEquals(listOf(7L), state.tokens.map { it.id })
    }

    @Test
    fun `revoking this device with current_token true signs out locally`() {
        gateway.list = TokenListResult.Loaded(listOf(tablet, thisPhone))
        gateway.revoke = RevokeResult.Revoked(currentToken = true)
        val vm = viewModel()

        vm.askRevoke(thisPhone)
        vm.confirmRevoke()
        assertEquals(SignOutNotice.ThisDeviceRevoked, vm.state.value.signOutNotice)

        var signedOut = 0
        vm.confirmSignOut { signedOut++ }
        assertEquals(1, signedOut)
        assertNull(vm.state.value.signOutNotice)
    }

    @Test
    fun `a failed revoke keeps the token and the session`() {
        gateway.list = TokenListResult.Loaded(listOf(thisPhone))
        gateway.revoke = RevokeResult.Failed(offline = true)
        val vm = viewModel()

        vm.askRevoke(thisPhone)
        vm.confirmRevoke()

        assertNull(vm.state.value.signOutNotice)
        assertEquals(listOf(7L), vm.state.value.tokens.map { it.id })
        assertNull(vm.state.value.revokingId)
    }

    @Test
    fun `password change success explains and then signs out`() {
        val vm = viewModel()
        vm.setPasswordOpen(true)

        vm.changePassword("old-password", "new-password-123", "new-password-123")

        assertEquals(1, gateway.passwordCalls)
        assertEquals(SignOutNotice.PasswordChanged, vm.state.value.signOutNotice)
        assertFalse(vm.state.value.passwordOpen)
        assertFalse(vm.state.value.passwordBusy)

        var signedOut = false
        vm.confirmSignOut { signedOut = true }
        assertTrue(signedOut)
        assertNull(vm.state.value.signOutNotice)
    }

    @Test
    fun `password validation errors are inline and send nothing`() {
        val vm = viewModel()

        vm.changePassword("", "short", "other")

        val errors = vm.state.value.passwordErrors
        assertEquals(0, gateway.passwordCalls)
        assertEquals(R.string.account_password_err_current_required, errors.current)
        assertEquals(R.string.account_password_err_new_short, errors.new)
        assertEquals(R.string.account_password_err_mismatch, errors.confirm)
        assertNull(vm.state.value.signOutNotice)
    }

    @Test
    fun `a wrong current password stays signed in with an inline error`() {
        gateway.password = PasswordChangeResult.CurrentIncorrect
        val vm = viewModel()

        vm.changePassword("wrong", "new-password-123", "new-password-123")

        assertEquals(R.string.account_password_err_current, vm.state.value.passwordErrors.current)
        assertNull(vm.state.value.signOutNotice)
    }

    @Test
    fun `validation accepts a good password change`() {
        assertNull(validatePasswordChange("old", "123456789012", "123456789012"))
        assertNotNull(validatePasswordChange("old", "12345678901", "12345678901"))
    }

    @Test
    fun `avatar upload errors go to the snackbar bus`() =
        runTest(UnconfinedTestDispatcher()) {
            val seen = mutableListOf<Int>()
            backgroundScope.launch { feedback.events.collect { seen += it.messageResId } }
            gateway.avatar = AvatarResult.TooLarge
            val vm = viewModel()

            vm.uploadAvatar("content://pick/1")

            assertFalse(vm.state.value.avatarBusy)
            assertEquals(listOf(R.string.error_avatar_too_large), seen)
        }

    @Test
    fun `the profile load fills the avatar source and the Gravatar toggle`() {
        gateway.profile =
            AccountProfileResult.Loaded(
                AccountDto(
                    1,
                    "me@example.org",
                    avatarUrl = "/media/thumbnails/users/1/gravatar",
                    avatarSourceRaw = "gravatar",
                    useGravatar = true,
                    gravatarEnabled = true,
                )
            )
        val state = viewModel().state.value

        assertEquals(AvatarSource.GRAVATAR, state.avatarSource)
        assertTrue(state.gravatarEnabled)
        assertTrue(state.useGravatar)
    }

    @Test
    fun `a server without Gravatar keeps the toggle hidden`() {
        val state = viewModel().state.value

        assertFalse(state.gravatarEnabled)
        assertNull(state.avatarSource)
    }

    @Test
    fun `the toggle sends the preference and applies the answer`() {
        gateway.toggle =
            AccountProfileResult.Loaded(
                AccountDto(1, "me@example.org", useGravatar = false, gravatarEnabled = true)
            )
        val vm = viewModel()

        vm.setUseGravatar(false)

        assertEquals(listOf(false), gateway.toggled)
        assertFalse(vm.state.value.useGravatar)
        assertTrue(vm.state.value.gravatarEnabled)
        assertFalse(vm.state.value.gravatarBusy)
        assertNull(vm.state.value.avatarSource)
    }

    @Test
    fun `a failed toggle keeps the old value and tells the user`() =
        runTest(UnconfinedTestDispatcher()) {
            val seen = mutableListOf<Int>()
            backgroundScope.launch { feedback.events.collect { seen += it.messageResId } }
            val vm = viewModel()

            vm.setUseGravatar(false)

            assertTrue(vm.state.value.useGravatar)
            assertFalse(vm.state.value.gravatarBusy)
            assertEquals(listOf(R.string.error_gravatar_save_failed), seen)
        }

    @Test
    fun `the photo metadata setting is off until the server says otherwise`() {
        assertFalse(viewModel().state.value.stripPhotoMetadata)

        gateway.profile =
            AccountProfileResult.Loaded(AccountDto(1, "me@example.org", stripPhotoMetadata = true))
        assertTrue(viewModel().state.value.stripPhotoMetadata)
    }

    @Test
    fun `the photo metadata toggle sends the choice and applies the answer`() =
        runTest(UnconfinedTestDispatcher()) {
            val seen = mutableListOf<Int>()
            backgroundScope.launch { feedback.events.collect { seen += it.messageResId } }
            gateway.stripResult =
                AccountProfileResult.Loaded(
                    AccountDto(1, "me@example.org", stripPhotoMetadata = true)
                )
            val vm = viewModel()

            vm.setStripPhotoMetadata(true)

            assertEquals(listOf(true), gateway.stripped)
            assertTrue(vm.state.value.stripPhotoMetadata)
            assertFalse(vm.state.value.stripPhotoMetadataBusy)
            assertEquals(listOf(R.string.account_strip_metadata_on), seen)
        }

    @Test
    fun `a failed photo metadata toggle keeps the old value and tells the user`() =
        runTest(UnconfinedTestDispatcher()) {
            val seen = mutableListOf<Int>()
            backgroundScope.launch { feedback.events.collect { seen += it.messageResId } }
            val vm = viewModel()

            vm.setStripPhotoMetadata(true)

            assertFalse(vm.state.value.stripPhotoMetadata)
            assertFalse(vm.state.value.stripPhotoMetadataBusy)
            assertEquals(listOf(R.string.error_strip_metadata_save_failed), seen)
        }
}
