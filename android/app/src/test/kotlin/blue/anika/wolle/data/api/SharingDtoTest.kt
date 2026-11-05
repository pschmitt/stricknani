package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.dto.AccessDto
import blue.anika.wolle.data.api.dto.ProjectDto
import blue.anika.wolle.data.api.dto.ShareCreateRequest
import blue.anika.wolle.data.api.dto.ShareListDto
import blue.anika.wolle.data.api.dto.TransferRequest
import blue.anika.wolle.data.api.dto.TransferResultDto
import blue.anika.wolle.data.api.v2.ProjectV2Dto
import blue.anika.wolle.data.api.v2.V2Envelope
import blue.anika.wolle.data.api.v2.toV1
import blue.anika.wolle.data.repository.SharedKind
import blue.anika.wolle.data.repository.SharingError
import blue.anika.wolle.data.repository.sharingErrorOf
import blue.anika.wolle.data.repository.toEntity
import blue.anika.wolle.data.repository.transferRequest
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** The wire shapes of `docs/sharing-api.md`, as the app's Json (same flags as `NetworkModule`). */
class SharingDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val projectBody =
        """
        {"data": {"id": 4, "name": "Socks", "is_ai_enhanced": false, "is_favorite": false,
          "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-02T00:00:00Z",
          "owner": {"id": 7, "email": "", "name": "Anna", "avatar_url": "/api/v2/people/7/avatar/a.jpg"},
          "access": {"role": "viewer", "derived": false, "can_edit": false, "can_manage": false}}}
        """
            .trimIndent()

    @Test
    fun `a project carries its owner and access through the mapper into Room`() {
        val dto = json.decodeFromString<V2Envelope<ProjectV2Dto>>(projectBody).data.toV1()

        val entity = dto.toEntity(json)

        assertEquals(7L, entity.ownerId)
        assertEquals("Anna", entity.ownerName)
        assertEquals("viewer", entity.accessRole)
        assertFalse(entity.accessDerived)
        assertEquals(false, entity.canEdit)
        assertEquals(false, entity.canManage)
        // The full detail survives for the screens that decode it.
        assertEquals("viewer", json.decodeFromString<ProjectDto>(entity.detailJson).access?.role)
    }

    @Test
    fun `an item without access info decodes as null, meaning the user's own`() {
        val dto =
            json.decodeFromString<ProjectDto>(
                """{"id": 1, "name": "x", "is_ai_enhanced": false, "is_favorite": false,
                   "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-02T00:00:00Z"}"""
            )

        assertNull(dto.access)
        assertNull(dto.toEntity(json).accessRole)
    }

    @Test
    fun `a derived yarn access decodes`() {
        val access =
            json.decodeFromString<AccessDto>(
                """{"role":"viewer","derived":true,"can_edit":false,"can_manage":false}"""
            )

        assertTrue(access.derived)
        assertFalse(access.canEdit)
    }

    @Test
    fun `the share list and transfer result decode, people have no email field`() {
        val list =
            json
                .decodeFromString<V2Envelope<ShareListDto>>(
                    """{"data":{"owner":{"id":7,"name":"Anna","avatar_url":null},
                       "shares":[{"user":{"id":12,"name":"Ben","avatar_url":"/x"},"role":"editor",
                       "created_at":"c","updated_at":"u"}]}}"""
                )
                .data
        val result =
            json
                .decodeFromString<V2Envelope<TransferResultDto>>(
                    """{"data":{"new_owner":{"id":12,"name":"Ben"},
                       "access":{"role":"editor","derived":false,"can_edit":true,"can_manage":false},
                       "moved_yarn_ids":[3,5]}}"""
                )
                .data

        assertEquals("Anna", list.owner.name)
        assertEquals("editor", list.shares.single().role)
        assertEquals(12L, list.shares.single().user.id)
        assertEquals(listOf(3, 5), result.movedYarnIds)
        assertFalse(result.access.canManage)
    }

    @Test
    fun `the share body has exactly user_id and role`() {
        assertEquals(
            """{"user_id":12,"role":"viewer"}""",
            json.encodeToString(ShareCreateRequest(userId = 12, role = "viewer")),
        )
    }

    @Test
    fun `transfer sends move_linked_yarns only when it is set`() {
        assertEquals(
            """{"new_owner_id":12}""",
            json.encodeToString(TransferRequest(newOwnerId = 12)),
        )
        assertEquals(
            """{"new_owner_id":12,"move_linked_yarns":false}""",
            json.encodeToString(TransferRequest(newOwnerId = 12, moveLinkedYarns = false)),
        )
    }

    @Test
    fun `a yarn transfer never carries move_linked_yarns, a project transfer does`() {
        assertEquals(
            """{"new_owner_id":12}""",
            json.encodeToString(transferRequest(SharedKind.Yarn, 12, moveLinkedYarns = true)),
        )
        assertEquals(
            """{"new_owner_id":12,"move_linked_yarns":true}""",
            json.encodeToString(transferRequest(SharedKind.Project, 12, moveLinkedYarns = true)),
        )
    }

    @Test
    fun `failed calls map to what the sheet explains`() {
        fun http(code: Int) =
            HttpException(
                Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
            )

        assertEquals(SharingError.Forbidden, sharingErrorOf(http(403)))
        assertEquals(SharingError.NotFound, sharingErrorOf(http(404)))
        assertEquals(SharingError.Conflict, sharingErrorOf(http(409)))
        assertEquals(SharingError.Invalid, sharingErrorOf(http(422)))
        assertEquals(SharingError.Other, sharingErrorOf(http(500)))
        assertEquals(SharingError.Offline, sharingErrorOf(IOException("no route")))
    }
}
