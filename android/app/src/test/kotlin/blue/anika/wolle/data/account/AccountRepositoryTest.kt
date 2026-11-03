package blue.anika.wolle.data.account

import blue.anika.wolle.data.api.v2.V2Envelope
import java.io.IOException
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AccountRepositoryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private class FakeApi : AccountV2Api {
        var tokens: () -> V2Envelope<List<ApiTokenDto>> = { V2Envelope(emptyList()) }
        var create: (CreateTokenRequest) -> V2Envelope<CreatedTokenDto> = { error("unused") }
        var revoke: (Long) -> Response<V2Envelope<RevokedTokenDto>> = { error("unused") }
        var password: (ChangePasswordRequest) -> Unit = {}
        var avatar: () -> V2Envelope<AvatarDto> = { error("unused") }
        var lastCreate: CreateTokenRequest? = null
        var rename: (RenameTokenRequest) -> V2Envelope<ApiTokenDto> = { error("unused") }

        override suspend fun tokens() = tokens.invoke()

        override suspend fun createToken(request: CreateTokenRequest): V2Envelope<CreatedTokenDto> {
            lastCreate = request
            return create(request)
        }

        override suspend fun revokeToken(id: Long) = revoke(id)

        override suspend fun renameCurrentToken(request: RenameTokenRequest) = rename(request)

        override suspend fun changePassword(request: ChangePasswordRequest) = password(request)

        override suspend fun uploadAvatar(file: MultipartBody.Part) = avatar()

        override suspend fun deleteAvatar() = Unit
    }

    private fun http(status: Int, body: String = "{}") =
        HttpException(
            Response.error<Any>(status, body.toResponseBody("application/json".toMediaType()))
        )

    private fun error(code: String, fields: String = "") =
        """{"error":{"code":"$code","message":"m"$fields}}"""

    private val api = FakeApi()
    private val savedAvatar = mutableListOf<String?>()
    private val repository =
        AccountRepository(
            api,
            { savedAvatar += it },
            { AvatarUpload("a", "image/png", ByteArray(3)) },
        )

    @Test
    fun `token list parses name, dates and optional expiry, and never has a secret`() {
        val body =
            """{"data":[
              {"id":4,"name":"tablet","created_at":"2026-11-03T10:00:00Z","expires_at":null},
              {"id":9,"name":"phone","created_at":"2026-11-01T08:30:00Z","expires_at":"2027-01-01T00:00:00Z"}
            ]}"""
        val tokens = json.decodeFromString<V2Envelope<List<ApiTokenDto>>>(body).data

        assertEquals(listOf(4L, 9L), tokens.map { it.id })
        assertNull(tokens[0].expiresAt)
        assertEquals("2027-01-01T00:00:00Z", tokens[1].expiresAt)
    }

    @Test
    fun `token list parses last_used_at and tolerates servers that omit it`() {
        val body =
            """{"data":[
              {"id":4,"name":"tablet","created_at":"2026-11-03T10:00:00Z","last_used_at":"2026-11-04T09:15:00Z"},
              {"id":5,"name":"unused","created_at":"2026-11-03T10:00:00Z","last_used_at":null},
              {"id":9,"name":"old server","created_at":"2026-11-01T08:30:00Z"}
            ]}"""
        val tokens = json.decodeFromString<V2Envelope<List<ApiTokenDto>>>(body).data

        assertEquals("2026-11-04T09:15:00Z", tokens[0].lastUsedAt)
        assertNull(tokens[1].lastUsedAt)
        assertNull(tokens[2].lastUsedAt)
    }

    @Test
    fun `create response carries the secret once and its toString hides it`() {
        val body =
            """{"data":{"id":12,"name":"x","created_at":"2026-11-03T10:00:00Z","expires_at":"2026-12-01T00:00:00Z","token":"sk_secret_value"}}"""
        val created = json.decodeFromString<V2Envelope<CreatedTokenDto>>(body).data

        assertEquals("sk_secret_value", created.token)
        assertFalse(created.toString().contains("sk_secret_value"))
        assertFalse(created.summary().toString().contains("sk_secret_value"))
        assertEquals("2026-12-01T00:00:00Z", created.summary().expiresAt)
        assertFalse(CreateTokenRequest("n", "hunter2hunter2").toString().contains("hunter2"))
    }

    @Test
    fun `revoke body decodes current_token and defaults to false`() {
        val self =
            json.decodeFromString<V2Envelope<RevokedTokenDto>>(
                """{"data":{"id":5,"revoked":true,"current_token":true}}"""
            )
        val other =
            json.decodeFromString<V2Envelope<RevokedTokenDto>>(
                """{"data":{"id":6,"revoked":true}}"""
            )

        assertTrue(self.data.currentToken)
        assertFalse(other.data.currentToken)
    }

    @Test
    fun `404, 405 and 401 on the token list mean an older server, not an error`() = runTest {
        for (status in listOf(404, 405, 401)) {
            api.tokens = { throw http(status) }
            assertEquals(TokenListResult.Unsupported, repository.loadTokens())
        }
    }

    @Test
    fun `other failures keep the features and say whether it was the network`() = runTest {
        api.tokens = { throw http(500, error("internal_error")) }
        assertEquals(TokenListResult.Failed(offline = false), repository.loadTokens())

        api.tokens = { throw IOException("down") }
        assertEquals(TokenListResult.Failed(offline = true), repository.loadTokens())
    }

    @Test
    fun `a successful list is returned as is`() = runTest {
        val row = ApiTokenDto(1, "a", "2026-11-03T10:00:00Z")
        api.tokens = { V2Envelope(listOf(row)) }

        assertEquals(TokenListResult.Loaded(listOf(row)), repository.loadTokens())
    }

    @Test
    fun `create sends the trimmed name and current password and maps step-up errors`() = runTest {
        api.create = { throw http(401, error("invalid_credentials")) }
        assertEquals(CreateTokenResult.WrongPassword, repository.createToken(" tablet ", "pw"))
        assertEquals(CreateTokenRequest("tablet", "pw"), api.lastCreate)

        api.create = { throw http(403, error("reauth_required")) }
        assertEquals(CreateTokenResult.ReauthRequired, repository.createToken("t", "pw"))

        api.create = { throw http(401, error("session_expired")) }
        assertEquals(CreateTokenResult.SessionExpired, repository.createToken("t", "pw"))

        api.create = { throw http(429, error("rate_limited")) }
        assertEquals(CreateTokenResult.RateLimited, repository.createToken("t", "pw"))

        api.create = { throw http(422, error("validation_error")) }
        assertEquals(CreateTokenResult.NameTooLong, repository.createToken("t", "pw"))
    }

    @Test
    fun `revoke maps current_token, an already gone token and failures`() = runTest {
        api.revoke = { Response.success(V2Envelope(RevokedTokenDto(1, true, true))) }
        assertEquals(RevokeResult.Revoked(currentToken = true), repository.revokeToken(1))

        api.revoke = { Response.success(V2Envelope(RevokedTokenDto(2, true, false))) }
        assertEquals(RevokeResult.Revoked(currentToken = false), repository.revokeToken(2))

        api.revoke = {
            Response.success<V2Envelope<RevokedTokenDto>>(
                null,
                okhttp3.Response.Builder()
                    .code(204)
                    .message("No Content")
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .request(okhttp3.Request.Builder().url("http://x/").build())
                    .build(),
            )
        }
        assertEquals(RevokeResult.Revoked(currentToken = false), repository.revokeToken(3))

        api.revoke = { Response.error(404, error("not_found").toResponseBody()) }
        assertEquals(RevokeResult.AlreadyGone, repository.revokeToken(4))

        api.revoke = { throw IOException("down") }
        assertEquals(RevokeResult.Failed(offline = true), repository.revokeToken(5))
    }

    @Test
    fun `password change maps the field errors of a 422`() = runTest {
        assertEquals(
            PasswordChangeResult.Changed,
            repository.changePassword("old", "new-password-123"),
        )

        api.password = {
            throw http(422, error("validation_error", ""","fields":{"current_password":"x"}"""))
        }
        assertEquals(PasswordChangeResult.CurrentIncorrect, repository.changePassword("o", "n"))

        api.password = {
            throw http(422, error("validation_error", ""","fields":{"new_password":"x"}"""))
        }
        assertEquals(PasswordChangeResult.NewRejected, repository.changePassword("o", "n"))

        api.password = { throw http(429, error("rate_limited")) }
        assertEquals(PasswordChangeResult.RateLimited, repository.changePassword("o", "n"))
    }

    @Test
    fun `avatar upload stores the new path and maps size and image errors`() = runTest {
        api.avatar = { V2Envelope(AvatarDto("/media/thumbnails/users/1/a.webp")) }
        assertEquals(
            AvatarResult.Uploaded("/media/thumbnails/users/1/a.webp"),
            repository.uploadAvatar("content://pick/1"),
        )
        assertEquals(listOf<String?>("/media/thumbnails/users/1/a.webp"), savedAvatar)

        api.avatar = { throw http(413, error("payload_too_large")) }
        assertEquals(AvatarResult.TooLarge, repository.uploadAvatar("content://pick/1"))

        api.avatar = { throw http(400, error("invalid_image")) }
        assertEquals(AvatarResult.UnsupportedImage, repository.uploadAvatar("content://pick/1"))

        val oversized =
            AccountRepository(
                api,
                { savedAvatar += it },
                { AvatarUpload("a", "image/png", ByteArray(MAX_AVATAR_BYTES + 1)) },
            )
        assertEquals(AvatarResult.TooLarge, oversized.uploadAvatar("content://pick/2"))
    }

    @Test
    fun `removing the avatar clears the saved path`() = runTest {
        assertEquals(AvatarResult.Removed, repository.deleteAvatar())
        assertEquals(listOf<String?>(null), savedAvatar)
    }

    @Test
    fun `token dates are medium local dates and fall back to the raw text`() {
        assertEquals(
            "Nov 3, 2026",
            formatTokenDate("2026-11-03T10:00:00Z", Locale.US, ZoneOffset.UTC),
        )
        assertEquals("soon", formatTokenDate("soon", Locale.US, ZoneOffset.UTC))
    }
}
