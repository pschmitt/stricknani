package blue.anika.wolle.data.repository

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AdminRepositoryTest {
    private fun http(status: Int, body: String) =
        HttpException(
            Response.error<Any>(status, body.toResponseBody("application/json".toMediaType()))
        )

    @Test
    fun `401 and 403 mean not allowed`() {
        assertEquals(
            AdminFailure.NotAllowed,
            http(403, """{"error":{"code":"admin_required"}}""").toAdminFailure(),
        )
        assertEquals(AdminFailure.NotAllowed, http(401, "").toAdminFailure())
    }

    @Test
    fun `error codes map to specific failures`() {
        assertEquals(
            AdminFailure.LastAdmin,
            http(409, """{"error":{"code":"admin_conflict"}}""").toAdminFailure(),
        )
        assertEquals(
            AdminFailure.EmailInUse,
            http(409, """{"error":{"code":"email_in_use"}}""").toAdminFailure(),
        )
        assertEquals(
            AdminFailure.InvalidEmail,
            http(400, """{"error":{"code":"invalid_email"}}""").toAdminFailure(),
        )
        assertEquals(
            AdminFailure.PasswordTooShort,
            http(400, """{"error":{"code":"invalid_password"}}""").toAdminFailure(),
        )
        assertEquals(
            AdminFailure.PasswordTooCommon,
            http(400, """{"error":{"code":"common_password"}}""").toAdminFailure(),
        )
        assertEquals(
            AdminFailure.NotFound,
            http(404, """{"error":{"code":"not_found"}}""").toAdminFailure(),
        )
        assertEquals(AdminFailure.Other, http(500, "not json").toAdminFailure())
    }

    @Test
    fun `network errors are offline, also when wrapped`() {
        assertEquals(AdminFailure.Offline, IOException("no route").toAdminFailure())
        assertEquals(AdminFailure.Offline, RuntimeException(IOException("x")).toAdminFailure())
        assertEquals(AdminFailure.Other, IllegalStateException("x").toAdminFailure())
    }
}
