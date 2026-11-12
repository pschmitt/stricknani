package blue.anika.wolle.ui.settings

import blue.anika.wolle.R
import blue.anika.wolle.data.account.AccountDeletionGateway
import blue.anika.wolle.data.account.DeleteAccountResult
import blue.anika.wolle.data.oidc.FakeAccountApi
import blue.anika.wolle.data.oidc.FakeLoginApi
import blue.anika.wolle.data.oidc.FakeSession
import blue.anika.wolle.data.oidc.OidcCoordinator
import blue.anika.wolle.data.oidc.PendingOidcFlow
import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.ui.common.FakeDangerousActionGuard
import blue.anika.wolle.ui.common.MutationFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class DeleteAccountViewModelTest {
    private class FakeDeletion : AccountDeletionGateway {
        val calls = mutableListOf<String>()

        override suspend fun deleteAccount(
            confirmation: String,
            proof: ReauthProof,
        ): DeleteAccountResult {
            calls += confirmation
            return DeleteAccountResult.Deleted
        }
    }

    private val gateway = FakeDeletion()
    private val guard = FakeDangerousActionGuard()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): DeleteAccountViewModel {
        val coordinator =
            OidcCoordinator(
                FakeLoginApi(),
                FakeAccountApi(),
                PendingOidcFlow(),
                FakeSession(hasSession = true, sessionServerUrl = "https://s"),
                CoroutineScope(UnconfinedTestDispatcher()),
                afterSignIn = {},
                tokenName = { "Android test" },
                newState = { "state-1" },
                newVerifier = { "v".repeat(64) },
            )
        return DeleteAccountViewModel(
            gateway,
            coordinator,
            { "me@example.org" },
            { "https://s" },
            MutationFeedback(),
            guard,
        )
    }

    private fun DeleteAccountViewModel.typedDelete() {
        open()
        onConfirmationChange("DELETE")
    }

    @Test
    fun `without a successful unlock the deletion does not start`() {
        guard.unlocked = false
        val vm = viewModel()
        vm.typedDelete()

        vm.confirm()

        assertEquals(listOf(R.string.unlock_reason_delete_account), guard.requests)
        // No password sheet, so no proof, so nothing can be sent.
        assertNull(vm.sheet.value)
        vm.submitPassword("secret")
        assertTrue(gateway.calls.isEmpty())
        assertFalse(vm.state.value.deleted)
    }

    @Test
    fun `after the unlock the password sheet opens and the account is deleted`() {
        val vm = viewModel()
        vm.typedDelete()

        vm.confirm()

        assertEquals(ReauthAction.DELETE_ACCOUNT, vm.sheet.value?.request?.action)
        assertTrue(gateway.calls.isEmpty())
        vm.submitPassword("secret")
        assertEquals(listOf("DELETE"), gateway.calls)
        assertTrue(vm.state.value.deleted)
    }

    @Test
    fun `a wrong typed confirmation never asks for the unlock`() {
        val vm = viewModel()
        vm.open()
        vm.onConfirmationChange("nope")

        vm.confirm()

        assertTrue(guard.requests.isEmpty())
        assertNull(vm.sheet.value)
        assertNotNull(vm.state.value)
    }
}
