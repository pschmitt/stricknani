package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ApiGeneration
import blue.anika.wolle.data.api.dto.ApiErrorEnvelopeDto
import blue.anika.wolle.data.api.dto.ProjectListItemDto
import blue.anika.wolle.data.api.dto.TrashItemDto
import blue.anika.wolle.data.api.dto.TrashListDto
import blue.anika.wolle.data.api.dto.YarnListItemDto
import blue.anika.wolle.data.api.v2.ProjectsV2Api
import blue.anika.wolle.data.api.v2.TrashV2Api
import blue.anika.wolle.data.api.v2.V2ListEnvelope
import blue.anika.wolle.data.api.v2.YarnsV2Api
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * What can sit in the trash. [wire] is the `kind` of the API, [segment] the plural used in the
 * restore and delete-forever URLs.
 */
enum class TrashKind(val wire: String, val segment: String) {
    Project("project", "projects"),
    Yarn("yarn", "yarns"),
    Category("category", "categories"),
    Step("step", "steps"),
    Image("image", "images"),
    Attachment("attachment", "attachments"),
    Photo("photo", "photos");

    companion object {
        /** Null for a kind this app version does not know (the server may add more). */
        fun fromWire(kind: String): TrashKind? = entries.firstOrNull { it.wire == kind }
    }
}

/** The URL segment of any kind, known or not (an unknown kind is guessed as its plural). */
fun trashSegment(kind: String): String = TrashKind.fromWire(kind)?.segment ?: "${kind}s"

/** An item of the trash, addressed the way the restore and delete-forever endpoints do. */
data class TrashRef(val kind: String, val id: Long) {
    constructor(kind: TrashKind, id: Int) : this(kind.wire, id.toLong())

    val segment: String
        get() = trashSegment(kind)
}

/** Why a trash request did not work, in terms the screens can explain. */
sealed interface TrashFailure {
    /** No connection, or the server could not be reached. */
    data object Offline : TrashFailure

    /** 401/403: not the owner, not an administrator. */
    data object NotAllowed : TrashFailure

    /** 404: not in the trash any more (restored, deleted forever, or never yours). */
    data object NotFound : TrashFailure

    /** 409 `parent_in_trash`: restore the project or yarn first. */
    data object ParentInTrash : TrashFailure

    /** 409 `name_taken`: a category with that name exists again; rename one of them first. */
    data object NameTaken : TrashFailure

    /** The server has no trash endpoint (no v2, or 404/405 on the list). */
    data object Unsupported : TrashFailure

    data object Other : TrashFailure
}

class TrashException(val failure: TrashFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)

private val TRASH_ERROR_JSON = Json { ignoreUnknownKeys = true }

fun trashFailureFor(status: Int, code: String?): TrashFailure =
    when {
        status == 409 && code == "parent_in_trash" -> TrashFailure.ParentInTrash
        status == 409 && code == "name_taken" -> TrashFailure.NameTaken
        status == 401 || status == 403 -> TrashFailure.NotAllowed
        status == 404 -> TrashFailure.NotFound
        else -> TrashFailure.Other
    }

/** Turns any thrown request error into a [TrashFailure]. Pure, so it is unit-tested. */
fun Throwable.toTrashFailure(json: Json = TRASH_ERROR_JSON): TrashFailure {
    if (this is TrashException) return failure
    if (this is HttpException) {
        val code =
            runCatching {
                    json
                        .decodeFromString<ApiErrorEnvelopeDto>(
                            response()?.errorBody()?.string().orEmpty()
                        )
                        .error
                        ?.code
                }
                .getOrNull()
        return trashFailureFor(code(), code)
    }
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return TrashFailure.Offline
        current = current.cause
    }
    return TrashFailure.Other
}

private const val TRASHED_ONLY_PARAM = "only"

/** The trash holds at most a few hundred items per owner; a runaway server cannot loop forever. */
private const val MAX_TRASH_PAGES = 20

/** The trash. Online-only: nothing is cached in Room; a restored item returns by a normal sync. */
@Singleton
class TrashRepository
@Inject
constructor(
    private val api: TrashV2Api,
    private val projectsApi: ProjectsV2Api,
    private val yarnsApi: YarnsV2Api,
    private val capabilities: ApiCapabilities,
) {
    suspend fun list(scope: String): TrashListDto = call {
        if (capabilities.resolve().generation != ApiGeneration.V2) {
            throw TrashException(TrashFailure.Unsupported)
        }
        api.list(scope)
    }

    /**
     * The trashed projects (`GET /api/v2/projects?trashed=only`) for the Trash filter of the
     * project list. Online-only, like everything here: trashed items are not in Room. [owner] is
     * `me`, `shared` or a user id; [scope] is `mine` or `all`.
     */
    suspend fun listTrashedProjects(
        owner: String?,
        status: String?,
        scope: String,
    ): List<ProjectListItemDto> = allPages { page ->
        projectsApi.listProjects(
            page = page,
            status = status,
            owner = owner,
            trashed = TRASHED_ONLY_PARAM,
            scope = scope,
        )
    }

    /** The trashed yarns (`GET /api/v2/yarns?trashed=only`); see [listTrashedProjects]. */
    suspend fun listTrashedYarns(owner: String?, scope: String): List<YarnListItemDto> =
        allPages { page ->
            yarnsApi.listYarns(
                page = page,
                owner = owner,
                trashed = TRASHED_ONLY_PARAM,
                scope = scope,
            )
        }

    private suspend fun <T> allPages(fetch: suspend (Int) -> V2ListEnvelope<T>): List<T> = call {
        if (capabilities.resolve().generation != ApiGeneration.V2) {
            throw TrashException(TrashFailure.Unsupported)
        }
        val items = mutableListOf<T>()
        var page = 1
        while (page <= MAX_TRASH_PAGES) {
            val envelope = fetch(page)
            items += envelope.data
            if (!envelope.meta.hasMore) break
            page++
        }
        items
    }

    suspend fun restore(ref: TrashRef) {
        call { api.restore(ref.segment, ref.id) }
    }

    suspend fun purge(ref: TrashRef) {
        call { api.purge(ref.segment, ref.id) }
    }

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw TrashException(failure.toTrashFailure(), failure)
        }
}

fun TrashItemDto.ref(): TrashRef = TrashRef(kind, id)
