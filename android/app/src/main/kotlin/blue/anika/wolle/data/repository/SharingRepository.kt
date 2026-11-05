package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.dto.PeoplePageDto
import blue.anika.wolle.data.api.dto.ShareCreateRequest
import blue.anika.wolle.data.api.dto.ShareDto
import blue.anika.wolle.data.api.dto.ShareListDto
import blue.anika.wolle.data.api.dto.ShareRoleRequest
import blue.anika.wolle.data.api.dto.TransferRequest
import blue.anika.wolle.data.api.dto.TransferResultDto
import blue.anika.wolle.data.api.dto.YarnCandidatesPageDto
import blue.anika.wolle.data.api.v2.SharingV2Api
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** What a share can be attached to; [path] is the route segment of `docs/sharing-api.md`. */
enum class SharedKind(val path: String) {
    Project("projects"),
    Yarn("yarns"),
}

/** The two roles a person can hold on a shared item. */
enum class ShareRole(val wire: String) {
    Viewer("viewer"),
    Editor("editor");

    companion object {
        fun fromWire(value: String): ShareRole = if (value == "editor") Editor else Viewer
    }
}

/** Why a sharing call failed, in the terms the sheet explains to the user. */
enum class SharingError {
    /** No connection. Sharing is never queued. */
    Offline,
    /** 403: the caller may no longer manage this item. */
    Forbidden,
    /** 404: the item or share is gone or no longer visible. */
    NotFound,
    /** 409: already shared, or the item changed owner meanwhile. */
    Conflict,
    /** 422: bad person or role. */
    Invalid,
    Other,
}

class SharingException(val error: SharingError, cause: Throwable? = null) :
    Exception(error.name, cause)

/** Maps a failed call to a [SharingError] without leaking server text. */
internal fun sharingErrorOf(failure: Throwable): SharingError =
    when (failure) {
        is HttpException ->
            when (failure.code()) {
                403 -> SharingError.Forbidden
                404 -> SharingError.NotFound
                409 -> SharingError.Conflict
                422 -> SharingError.Invalid
                else -> SharingError.Other
            }
        is IOException -> SharingError.Offline
        else -> SharingError.Other
    }

/** The calls of the Share sheet; an interface so the sheet's logic is testable without a server. */
interface SharingGateway {
    suspend fun shares(kind: SharedKind, id: Int): ShareListDto

    suspend fun add(kind: SharedKind, id: Int, userId: Long, role: ShareRole): ShareDto

    suspend fun changeRole(kind: SharedKind, id: Int, userId: Long, role: ShareRole): ShareDto

    suspend fun revoke(kind: SharedKind, id: Int, userId: Long)

    suspend fun leave(kind: SharedKind, id: Int)

    suspend fun transfer(
        kind: SharedKind,
        id: Int,
        newOwnerId: Long,
        moveLinkedYarns: Boolean,
    ): TransferResultDto

    suspend fun people(query: String?, page: Int = 1): PeoplePageDto

    suspend fun yarnCandidates(projectId: Int, query: String?, page: Int = 1): YarnCandidatesPageDto
}

/**
 * Sharing and ownership transfer. Every call needs the network (an offline attempt fails with
 * [SharingError.Offline], never queued) and the server decides who may do what; the sheet only
 * mirrors the synced `access` flags. After a call that changes the caller's own access the cache is
 * refreshed from the server so the screen shows the real state.
 */
@Singleton
class SharingRepository
@Inject
constructor(
    private val api: SharingV2Api,
    private val projects: ProjectRepository,
    private val yarns: YarnRepository,
) : SharingGateway {
    override suspend fun shares(kind: SharedKind, id: Int): ShareListDto = call {
        api.listShares(kind.path, id).data
    }

    override suspend fun add(kind: SharedKind, id: Int, userId: Long, role: ShareRole): ShareDto =
        call {
            api.addShare(kind.path, id, ShareCreateRequest(userId, role.wire)).data
        }

    override suspend fun changeRole(
        kind: SharedKind,
        id: Int,
        userId: Long,
        role: ShareRole,
    ): ShareDto = call {
        api.changeRole(kind.path, id, userId, ShareRoleRequest(role.wire)).data
    }

    override suspend fun revoke(kind: SharedKind, id: Int, userId: Long) {
        call { api.revoke(kind.path, id, userId) }
    }

    /** Leaves a share; the local row and its media go at once. */
    override suspend fun leave(kind: SharedKind, id: Int) {
        call { api.leave(kind.path, id) }
        when (kind) {
            SharedKind.Project -> projects.forgetLocally(id)
            SharedKind.Yarn -> yarns.forgetLocally(id)
        }
    }

    /**
     * Hands the item to [newOwnerId]. The caller stays an editor; the cache re-reads the item (and
     * the yarns that moved with a project) so owner and access are the server's.
     */
    override suspend fun transfer(
        kind: SharedKind,
        id: Int,
        newOwnerId: Long,
        moveLinkedYarns: Boolean,
    ): TransferResultDto {
        val request = transferRequest(kind, newOwnerId, moveLinkedYarns)
        val result = call { api.transfer(kind.path, id, request).data }
        runCatching {
            when (kind) {
                SharedKind.Project -> projects.refreshOne(id)
                SharedKind.Yarn -> yarns.refreshOne(id)
            }
        }
        result.movedYarnIds.forEach { runCatching { yarns.refreshOne(it) } }
        return result
    }

    override suspend fun people(query: String?, page: Int): PeoplePageDto = call {
        api.people(query?.trim()?.take(MAX_QUERY)?.takeIf { it.isNotEmpty() }, page).data
    }

    /** The owner's yarns an editor may link to a shared project. */
    override suspend fun yarnCandidates(
        projectId: Int,
        query: String?,
        page: Int,
    ): YarnCandidatesPageDto = call {
        api.yarnCandidates(projectId, query?.trim()?.takeIf { it.isNotEmpty() }, page).data
    }

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw SharingException(sharingErrorOf(failure), failure)
        }

    private companion object {
        const val MAX_QUERY = 64
    }
}

/** The transfer body: `move_linked_yarns` exists for projects only (`true` on a yarn is a 422). */
internal fun transferRequest(
    kind: SharedKind,
    newOwnerId: Long,
    moveLinkedYarns: Boolean,
): TransferRequest =
    TransferRequest(
        newOwnerId = newOwnerId,
        moveLinkedYarns = moveLinkedYarns.takeIf { kind == SharedKind.Project },
    )
