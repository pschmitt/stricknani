package blue.anika.wolle.data.account

import blue.anika.wolle.data.api.v2.V2Envelope
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DeviceTokenRenamerTest {
    private class Api(var failure: Exception? = null) : AccountV2Api {
        val renames = mutableListOf<String>()

        override suspend fun renameCurrentToken(
            request: RenameTokenRequest
        ): V2Envelope<ApiTokenDto> {
            renames += request.name
            failure?.let { throw it }
            return V2Envelope(ApiTokenDto(1, request.name, "2026-11-03T10:00:00Z"))
        }

        override suspend fun tokens() = error("unused")

        override suspend fun createToken(request: CreateTokenRequest) = error("unused")

        override suspend fun revokeToken(id: Long): Response<V2Envelope<RevokedTokenDto>> =
            error("unused")

        override suspend fun changePassword(request: ChangePasswordRequest) = error("unused")

        override suspend fun uploadAvatar(file: MultipartBody.Part) = error("unused")

        override suspend fun deleteAvatar() = error("unused")
    }

    private var pending = false
    private val api = Api()

    private fun renamer(api: Api = this.api) =
        DeviceTokenRenamer(api, { "Pixel 9" }, { pending }, { pending = it })

    private fun http(status: Int) =
        HttpException(
            Response.error<Any>(status, "{}".toResponseBody("application/json".toMediaType()))
        )

    @Test
    fun `a QR setup renames the token exactly once`() = runTest {
        val renamer = renamer()

        assertEquals(RenameOutcome.Renamed, renamer.renameAfterSetup())
        assertEquals(listOf("Android · Pixel 9"), api.renames)
        assertFalse(pending)
        assertNull(renamer.renameIfPending())
        assertEquals(1, api.renames.size)
    }

    @Test
    fun `a normal login never renames`() = runTest {
        // Password and OIDC logins never mark the flag, the name is already right.
        assertNull(renamer().renameIfPending())
        assertEquals(emptyList<String>(), api.renames)
    }

    @Test
    fun `an unsupported server is silent and not retried`() = runTest {
        for (status in listOf(401, 404, 405)) {
            val failing = Api(http(status))
            assertEquals(RenameOutcome.Unsupported, renamer(failing).renameAfterSetup())
            assertFalse(pending)
            assertNull(renamer(failing).renameIfPending())
            assertEquals(1, failing.renames.size)
        }
    }

    @Test
    fun `validation errors and offline are swallowed`() = runTest {
        assertEquals(RenameOutcome.Failed, renamer(Api(http(422))).renameAfterSetup())
        assertEquals(RenameOutcome.Failed, renamer(Api(IOException("offline"))).renameAfterSetup())
        assertFalse(pending)
    }
}
