package blue.anika.wolle.ui.passkey

import blue.anika.wolle.data.oidc.FakeAccountApi
import blue.anika.wolle.data.oidc.FakeLoginApi as FakeOidcLoginApi
import blue.anika.wolle.data.oidc.FakeSession
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.PendingOidcFlow
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import blue.anika.wolle.data.passkey.FakeGateway
import blue.anika.wolle.data.passkey.FakeLoginApi
import blue.anika.wolle.data.passkey.FakePasskeyApi
import blue.anika.wolle.data.passkey.NoWindow
import blue.anika.wolle.data.passkey.PasskeyFailure
import blue.anika.wolle.data.passkey.PasskeyOutcome
import blue.anika.wolle.data.passkey.PasskeyRepository
import blue.anika.wolle.ui.oidc.ReauthRequest
import blue.anika.wolle.ui.oidc.StepUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared step-up sheet logic with a passkey, for the database restore. */
@OptIn(ExperimentalCoroutinesApi::class)
class StepUpPasskeyTest {
    private val api = FakePasskeyApi()
    private val gateway = FakeGateway()
    private val proofs = mutableListOf<ReauthProof>()
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private val coordinator =
        OidcCoordinator(
            FakeOidcLoginApi(),
            FakeAccountApi(),
            PendingOidcFlow(),
            FakeSession(hasSession = true, sessionServerUrl = "https://s"),
            scope,
            afterSignIn = {},
            tokenName = { "Android test" },
            newState = { "state-1" },
            newVerifier = { "v".repeat(64) },
        )
    private val stepUp =
        StepUp(
            coordinator,
            scope,
            onProof = { proofs += it },
            onFailure = {},
            passkeys =
                PasskeyRepository(api, FakeLoginApi(), gateway, Json { ignoreUnknownKeys = true }),
        )

    private fun request(action: ReauthAction = ReauthAction.RESTORE_BACKUP) =
        ReauthRequest(
            action = action,
            target = ReauthTarget.None,
            canUsePassword = true,
            oidcProvider = null,
        )

    private val everyAction =
        listOf(
            ReauthAction.LINK_IDENTITY,
            ReauthAction.UNLINK_IDENTITY,
            ReauthAction.ADMIN_UNLINK_IDENTITY,
            ReauthAction.DELETE_ACCOUNT,
            ReauthAction.CREATE_API_TOKEN,
            ReauthAction.RESTORE_BACKUP,
            ReauthAction.MANAGE_PASSKEYS,
        )

    @Test
    fun `every sheet offers the passkey when the device and the account can`() {
        everyAction.forEach { action ->
            stepUp.begin(request(action))
            assertTrue("$action", stepUp.sheet.value!!.request.canUsePasskey)
        }
    }

    @Test
    fun `the passkey option is hidden when the account has no passkey`() {
        api.passkeys.clear()
        everyAction.forEach { action ->
            stepUp.begin(request(action))
            assertFalse("$action", stepUp.sheet.value!!.request.canUsePasskey)
        }
    }

    @Test
    fun `the passkey option is hidden on a device without Credential Manager`() {
        gateway.supported = false
        everyAction.forEach { action ->
            stepUp.begin(request(action))
            assertFalse("$action", stepUp.sheet.value!!.request.canUsePasskey)
        }
    }

    @Test
    fun `a caller cannot force the passkey option on`() {
        api.passkeys.clear()
        stepUp.begin(request().copy(canUsePasskey = true))
        assertFalse(stepUp.sheet.value!!.request.canUsePasskey)
    }

    @Test
    fun `without a passkey service there is no passkey option`() {
        val plain = StepUp(coordinator, scope, onProof = {}, onFailure = {})
        plain.begin(request())
        assertFalse(plain.sheet.value!!.request.canUsePasskey)
        plain.usePasskey(NoWindow)
        assertNotNull(plain.sheet.value)
    }

    @Test
    fun `a passkey confirms the database restore and yields a restore proof`() {
        stepUp.begin(request())

        stepUp.usePasskey(NoWindow)

        assertNull(stepUp.sheet.value)
        val proof = proofs.single()
        assertEquals(ReauthAction.RESTORE_BACKUP, proof.action)
        assertEquals("restore_backup", api.reauthRequest?.action)
    }

    @Test
    fun `the passkey proof is bound to the action of the sheet`() {
        stepUp.begin(request(ReauthAction.DELETE_ACCOUNT))
        stepUp.usePasskey(NoWindow)
        assertEquals(ReauthAction.DELETE_ACCOUNT, proofs.single().action)
        assertEquals("delete_account", api.reauthRequest?.action)
    }

    @Test
    fun `a dismissed passkey sheet keeps the confirmation open`() {
        gateway.got = PasskeyOutcome.Failed(PasskeyFailure.Cancelled)
        stepUp.begin(request())
        stepUp.usePasskey(NoWindow)
        assertNotNull(stepUp.sheet.value)
        assertNull(stepUp.sheet.value?.passkeyError)
        assertEquals(emptyList<ReauthProof>(), proofs)
    }
}
