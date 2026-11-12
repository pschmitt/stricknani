package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.AdminApi
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.dto.AdminUserDto
import blue.anika.wolle.data.api.dto.AdminUserEnvelopeDto
import blue.anika.wolle.data.api.dto.AdminUsersPageDto
import blue.anika.wolle.data.api.dto.AuditDetailEnvelopeDto
import blue.anika.wolle.data.api.dto.AuditPageDto
import blue.anika.wolle.data.api.dto.CreateAdminUserRequest
import blue.anika.wolle.data.api.dto.ResetPasswordEnvelopeDto
import blue.anika.wolle.data.api.dto.ResetPasswordRequest
import blue.anika.wolle.data.api.dto.SetUserActiveRequest
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.MetaV2Dto
import blue.anika.wolle.data.api.v2.MetaV2UserDto
import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.ui.admin.isCurrentUser
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * SNA-87: the Admin entry follows the current user's `is_admin`, with the user-list probe as
 * fallback.
 */
class AdminVisibilityTest {
    private fun http(status: Int) =
        HttpException(
            Response.error<Any>(
                status,
                // A v2 server always answers with an error envelope.
                """{"error":{"code":"error_$status","message":"x"}}"""
                    .toResponseBody("application/json".toMediaType()),
            )
        )

    private class FakeMeta(val respond: () -> MetaV2Dto) : MetaV2Api {
        override suspend fun getMeta() = V2Envelope(respond())
    }

    private class FakeAdminApi(val listUsersResult: () -> AdminUsersPageDto) : AdminApi {
        var listUsersCalls = 0

        override suspend fun listAudit(
            page: Int,
            entity: String?,
            action: String?,
            user: Long?,
        ): AuditPageDto = error("unused")

        override suspend fun getAuditEvent(id: Long): AuditDetailEnvelopeDto = error("unused")

        override suspend fun listUsers(page: Int): AdminUsersPageDto {
            listUsersCalls++
            return listUsersResult()
        }

        override suspend fun createUser(request: CreateAdminUserRequest): AdminUserEnvelopeDto =
            error("unused")

        override suspend fun setActive(
            id: Long,
            request: SetUserActiveRequest,
        ): AdminUserEnvelopeDto = error("unused")

        override suspend fun resetPassword(
            id: Long,
            request: ResetPasswordRequest,
        ): ResetPasswordEnvelopeDto = error("unused")
    }

    private fun repo(
        meta: () -> MetaV2Dto,
        api: FakeAdminApi,
        saved: MutableList<String> = mutableListOf(),
    ): AdminRepository {
        val caps = ApiCapabilities(FakeMeta(meta)) { "https://s\ntok" }
        return AdminRepository(api, caps, { "tok" }, { saved.lastOrNull() }, { saved += it })
    }

    private fun meta(isAdmin: Boolean) =
        MetaV2Dto(user = MetaV2UserDto(id = 5, email = "knit@example.org", isAdmin = isAdmin))

    @Test
    fun `an admin user shows the entry without asking the user list`() = runTest {
        val api = FakeAdminApi { error("must not be called") }
        val saved = mutableListOf<String>()
        val repository = repo({ meta(true) }, api, saved)

        assertEquals(true, repository.probeAdmin())
        assertEquals(true, repository.isAdmin.value)
        assertEquals(0, api.listUsersCalls)
        assertEquals(5L, repository.currentUserId)
        assertEquals("knit@example.org", repository.currentEmail)
        assertEquals(listOf("knit@example.org"), saved)
    }

    @Test
    fun `a non-admin user hides the entry`() = runTest {
        val api = FakeAdminApi { error("must not be called") }
        val repository = repo({ meta(false) }, api)

        assertEquals(false, repository.probeAdmin())
        assertEquals(0, api.listUsersCalls)
    }

    @Test
    fun `without v2 the user list probe decides`() = runTest {
        val allowed = FakeAdminApi {
            AdminUsersPageDto(
                emptyList(),
                blue.anika.wolle.data.api.dto.AdminPageMetaDto(1, 50, false),
            )
        }
        assertEquals(true, repo({ throw http(404) }, allowed).probeAdmin())
        assertEquals(1, allowed.listUsersCalls)

        val denied = FakeAdminApi { throw http(403) }
        assertEquals(false, repo({ throw http(404) }, denied).probeAdmin())
    }

    @Test
    fun `an unreachable server leaves the entry hidden and unknown`() = runTest {
        val api = FakeAdminApi { throw java.io.IOException("offline") }
        val repository = repo({ throw java.io.IOException("offline") }, api)

        assertNull(repository.probeAdmin())
        assertNull(repository.isAdmin.value)
    }

    @Test
    fun `an expired token on the meta probe hides the entry without asking the user list`() =
        runTest {
            val api = FakeAdminApi { error("must not be called") }
            val repository = repo({ throw http(401) }, api)

            assertNull(repository.probeAdmin())
            assertNull(repository.isAdmin.value)
            assertEquals(0, api.listUsersCalls)
        }

    @Test
    fun `You is matched by id when known, otherwise by email`() {
        val user = AdminUserDto(id = 5, email = "Other@Example.org", active = true, isAdmin = false)
        assertTrue(isCurrentUser(user, "x@y.z", currentUserId = 5))
        assertFalse(isCurrentUser(user, "other@example.org", currentUserId = 6))
        assertTrue(isCurrentUser(user, "other@example.org"))
        assertFalse(isCurrentUser(user, null))
    }
}
