package blue.anika.wolle.ui.settings

import blue.anika.wolle.R
import blue.anika.wolle.data.account.DeleteAccountResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeleteAccountMessageTest {
    @Test
    fun `every refusal explains itself and offline is told apart`() {
        assertEquals(
            R.string.delete_account_error_last_admin,
            DeleteAccountResult.LastAdministrator.messageRes(),
        )
        assertEquals(
            R.string.delete_account_error_confirmation,
            DeleteAccountResult.ConfirmationRejected.messageRes(),
        )
        assertEquals(
            R.string.delete_account_error_reauth,
            DeleteAccountResult.ReauthRequired.messageRes(),
        )
        assertEquals(
            R.string.delete_account_error_offline,
            DeleteAccountResult.Failed(offline = true).messageRes(),
        )
        assertEquals(
            R.string.delete_account_error_generic,
            DeleteAccountResult.Failed(offline = false).messageRes(),
        )
        assertNotEquals(
            DeleteAccountResult.Unsupported.messageRes(),
            DeleteAccountResult.SessionExpired.messageRes(),
        )
    }
}
