package blue.anika.wolle.data.account

import blue.anika.wolle.data.oidc.ReauthAction
import blue.anika.wolle.data.oidc.ReauthProof
import blue.anika.wolle.data.oidc.ReauthTarget
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AccountDeletionRepositoryTest {
    private class FakeApi : AccountDeletionApi {
        var failure: Exception? = null
        val calls = mutableListOf<Pair<String, DeleteAccountRequest>>()

        override suspend fun deleteAccount(reauthToken: String, request: DeleteAccountRequest) {
            calls += reauthToken to request
            failure?.let { throw it }
        }
    }

    private val api = FakeApi()
    private val repository = AccountDeletionRepository(api)
    private val proof = ReauthProof(ReauthAction.DELETE_ACCOUNT, ReauthTarget.None, "proof-1")

    private fun http(status: Int, code: String, fields: String = "") =
        HttpException(
            Response.error<Any>(
                status,
                """{"error":{"code":"$code","message":"m"$fields}}"""
                    .toResponseBody("application/json".toMediaType()),
            )
        )

    @Test
    fun `sends the proof in the header and the confirmation trimmed in the body`() = runTest {
        assertEquals(
            DeleteAccountResult.Deleted,
            repository.deleteAccount("  DELETE ", proof),
        )
        val (token, body) = api.calls.single()
        assertEquals("proof-1", token)
        assertEquals("DELETE", body.confirmation)
    }

    @Test
    fun `refuses a proof that was issued for something else without calling the server`() =
        runTest {
            val wrong = ReauthProof(ReauthAction.UNLINK_IDENTITY, ReauthTarget.Identity(1), "p")
            assertEquals(
                DeleteAccountResult.ReauthRequired,
                repository.deleteAccount("DELETE", wrong),
            )
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `maps the refusals to results`() = runTest {
        suspend fun result(failure: Exception): DeleteAccountResult {
            api.failure = failure
            return repository.deleteAccount("DELETE", proof)
        }
        assertEquals(
            DeleteAccountResult.ReauthRequired,
            result(http(403, "reauth_required")),
        )
        assertEquals(DeleteAccountResult.LastAdministrator, result(http(409, "last_admin")))
        assertEquals(
            DeleteAccountResult.ConfirmationRejected,
            result(http(422, "validation_error", ""","fields":{"confirmation":"x"}""")),
        )
        assertEquals(DeleteAccountResult.RateLimited, result(http(429, "rate_limited")))
        assertEquals(DeleteAccountResult.SessionExpired, result(http(401, "session_expired")))
        assertEquals(DeleteAccountResult.Unsupported, result(http(404, "not_found")))
        assertEquals(DeleteAccountResult.Unsupported, result(http(405, "method_not_allowed")))
        assertEquals(DeleteAccountResult.Failed(false), result(http(500, "internal_error")))
        assertEquals(DeleteAccountResult.Failed(true), result(IOException("offline")))
    }

    @Test
    fun `the typed confirmation is DELETE or the email in any case`() {
        val email = "Knitter@Example.test"
        assertTrue(deletionConfirmationMatches("DELETE", email))
        assertTrue(deletionConfirmationMatches(" DELETE ", email))
        assertTrue(deletionConfirmationMatches("knitter@example.TEST", email))
        assertFalse(deletionConfirmationMatches("", email))
        assertFalse(deletionConfirmationMatches("delete", email))
        assertFalse(deletionConfirmationMatches("someone@else.test", email))
        // An unknown email never makes an empty box match.
        assertFalse(deletionConfirmationMatches("", ""))
        assertFalse(deletionConfirmationMatches("x", ""))
    }
}
