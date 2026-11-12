package blue.anika.wolle.ui.passkey

import blue.anika.wolle.data.oidc.FakeAccountApi
import blue.anika.wolle.data.oidc.FakeLoginApi as FakeOidcLoginApi
import blue.anika.wolle.data.oidc.FakeSession
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.PendingOidcFlow
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.retrofitHttp
import blue.anika.wolle.data.passkey.FakeGateway
import blue.anika.wolle.data.passkey.FakeLoginApi
import blue.anika.wolle.data.passkey.FakePasskeyApi
import blue.anika.wolle.data.passkey.NoWindow
import blue.anika.wolle.data.passkey.PasskeyFailure
import blue.anika.wolle.data.passkey.PasskeyOutcome
import blue.anika.wolle.data.passkey.PasskeyRepository
import blue.anika.wolle.data.passkey.passkey
import blue.anika.wolle.ui.common.MutationFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PasskeysViewModelTest {
    private val api = FakePasskeyApi()
    private val gateway = FakeGateway()
    private val accountApi = FakeAccountApi()
    private val feedback = MutationFeedback()
    private val coordinator =
        OidcCoordinator(
            FakeOidcLoginApi(),
            accountApi,
            PendingOidcFlow(),
            FakeSession(hasSession = true, sessionServerUrl = "https://s"),
            CoroutineScope(UnconfinedTestDispatcher()),
            afterSignIn = {},
            tokenName = { "Android test" },
            newState = { "state-1" },
            newVerifier = { "v".repeat(64) },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): PasskeysViewModel =
        PasskeysViewModel(
            PasskeyRepository(
                api,
                FakeLoginApi(),
                gateway,
                Json { ignoreUnknownKeys = true },
            ),
            coordinator,
            feedback,
            newPasskeyName = { "Pixel 9" },
        )

    @Test
    fun `the card lists the account's passkeys`() {
        val vm = viewModel()
        val state = vm.state.value
        assertEquals(PasskeysPhase.Ready, state.phase)
        assertEquals(listOf("Pixel"), state.passkeys.map { it.nickname })
        assertEquals(10, state.limit)
        assertTrue(state.passwordEnabled)
        assertTrue(state.canAdd)
    }

    @Test
    fun `a server without passkeys hides the card and a failure offers a retry`() {
        api.error = retrofitHttp(404, "passkeys_unavailable")
        val vm = viewModel()
        assertEquals(PasskeysPhase.Hidden, vm.state.value.phase)

        api.error = retrofitHttp(500, "internal_error")
        vm.reload()
        assertEquals(PasskeysPhase.Failed, vm.state.value.phase)

        api.error = null
        vm.reload()
        assertEquals(PasskeysPhase.Ready, vm.state.value.phase)
    }

    @Test
    fun `adding confirms with the password first, then creates the passkey through the system sheet`() {
        val vm = viewModel()

        vm.onAddClicked()
        val request = vm.sheet.value!!.request
        assertEquals(ReauthAction.MANAGE_PASSKEYS, request.action)
        assertTrue(request.canUsePassword)
        assertTrue("the account has a passkey to confirm with", request.canUsePasskey)
        assertTrue("nothing is created before the confirmation", gateway.createRequests.isEmpty())

        vm.submitPassword("secret-password")
        assertNull(vm.sheet.value)
        assertEquals("manage_passkeys", accountApi.passwordReauths.single().action)
        assertTrue("the screen is asked for its window next", vm.state.value.addReady)
        assertTrue(gateway.createRequests.isEmpty())

        vm.completeAdd(NoWindow)

        val state = vm.state.value
        assertFalse(state.addReady)
        assertEquals(listOf("Pixel", "Pixel 9"), state.passkeys.map { it.nickname })
        assertEquals("the new passkey opens for renaming", "Pixel 9", state.renaming?.nickname)
        assertEquals(listOf("reauth-pw", "reauth-pw"), api.reauthTokens)
        assertEquals("Pixel 9", api.registered?.nickname)
        assertFalse(state.working)
    }

    @Test
    fun `completing twice does not create a second passkey`() {
        val vm = viewModel()
        vm.onAddClicked()
        vm.submitPassword("secret-password")
        vm.completeAdd(NoWindow)
        vm.completeAdd(NoWindow)
        assertEquals(1, gateway.createRequests.size)
    }

    @Test
    fun `a dismissed system sheet leaves the list alone`() {
        gateway.created = PasskeyOutcome.Failed(PasskeyFailure.Cancelled)
        val vm = viewModel()
        vm.onAddClicked()
        vm.submitPassword("secret-password")

        vm.completeAdd(NoWindow)

        assertEquals(listOf("Pixel"), vm.state.value.passkeys.map { it.nickname })
        assertNull(vm.state.value.renaming)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `a passkey can confirm the step up`() {
        val vm = viewModel()
        vm.onAddClicked()

        vm.confirmWithPasskey(NoWindow)

        assertNull(vm.sheet.value)
        assertEquals(1, gateway.getRequests.size)
        assertTrue(vm.state.value.addReady)
        assertEquals("manage_passkeys", api.reauthRequest?.action)
    }

    @Test
    fun `a dismissed confirmation keeps the sheet open without an error`() {
        gateway.got = PasskeyOutcome.Failed(PasskeyFailure.Cancelled)
        val vm = viewModel()
        vm.onAddClicked()

        vm.confirmWithPasskey(NoWindow)

        val sheet = vm.sheet.value!!
        assertNull(sheet.passkeyError)
        assertFalse(sheet.busy)
        assertFalse(vm.state.value.addReady)
    }

    @Test
    fun `a rejected confirmation shows why`() {
        gateway.got = PasskeyOutcome.Failed(PasskeyFailure.NoCredential)
        val vm = viewModel()
        vm.onAddClicked()

        vm.confirmWithPasskey(NoWindow)

        assertEquals(PasskeyFailure.NoCredential, vm.sheet.value!!.passkeyError)
    }

    @Test
    fun `without passkeys the step up offers only the password`() {
        api.passkeys.clear()
        val vm = viewModel()
        vm.onAddClicked()
        assertFalse(vm.sheet.value!!.request.canUsePasskey)
        vm.confirmWithPasskey(NoWindow)
        assertNotNull("ignored: the sheet stays", vm.sheet.value)
        assertTrue(gateway.getRequests.isEmpty())
    }

    @Test
    fun `removing asks first, then confirms, then deletes with the proof`() {
        val vm = viewModel()
        val pixel = vm.state.value.passkeys.single()

        vm.onRemoveClicked(pixel)
        assertEquals(pixel, vm.state.value.confirmRemove)
        assertNull(vm.sheet.value)
        assertFalse(api.calls.contains("delete"))

        vm.confirmRemove()
        assertNull(vm.state.value.confirmRemove)
        assertEquals(ReauthAction.MANAGE_PASSKEYS, vm.sheet.value!!.request.action)

        vm.submitPassword("secret-password")

        assertTrue(vm.state.value.passkeys.isEmpty())
        assertEquals(listOf("reauth-pw"), api.reauthTokens)
        assertTrue(api.calls.contains("delete"))
    }

    @Test
    fun `keeping a passkey deletes nothing`() {
        val vm = viewModel()
        vm.onRemoveClicked(vm.state.value.passkeys.single())
        vm.dismissRemove()
        assertNull(vm.state.value.confirmRemove)
        assertFalse(api.calls.contains("delete"))
    }

    @Test
    fun `dismissing the step up drops what was waiting for it`() {
        val vm = viewModel()
        vm.onAddClicked()
        vm.dismissSheet()
        assertNull(vm.sheet.value)
        assertFalse(vm.state.value.addReady)
        assertTrue(gateway.createRequests.isEmpty())
    }

    @Test
    fun `renaming updates the row without a step up`() {
        val vm = viewModel()
        val pixel = vm.state.value.passkeys.single()

        vm.onRenameClicked(pixel)
        assertEquals(pixel, vm.state.value.renaming)
        vm.rename("  Work phone ")

        assertNull(vm.state.value.renaming)
        assertEquals(listOf("Work phone"), vm.state.value.passkeys.map { it.nickname })
        assertNull(vm.sheet.value)
    }

    @Test
    fun `a blank name is not saved`() {
        val vm = viewModel()
        vm.onRenameClicked(vm.state.value.passkeys.single())
        vm.rename("   ")
        assertNotNull(vm.state.value.renaming)
        assertFalse(api.calls.contains("rename"))
    }

    @Test
    fun `at the limit adding is not offered`() {
        api.passkeys = MutableList(10) { passkey(it + 1L, "Key ${it + 1}") }
        val vm = viewModel()
        assertFalse(vm.state.value.canAdd)
        vm.onAddClicked()
        assertNull(vm.sheet.value)
    }
}
