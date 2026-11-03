package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ApiGeneration
import blue.anika.wolle.data.api.HistoryApi
import blue.anika.wolle.data.api.dto.AuditDetailDto
import blue.anika.wolle.data.api.dto.AuditPageDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

enum class HistoryKind {
    Project,
    Yarn,
}

/** Outcome of a history request, in terms the section can show. */
sealed interface HistoryResult<out T> {
    data class Ok<T>(val value: T) : HistoryResult<T>

    /** The server has no history endpoint (no v2, or 404/405): the section stays hidden. */
    data object Unsupported : HistoryResult<Nothing>

    data class Failed(val offline: Boolean) : HistoryResult<Nothing>
}

/** Pure mapping of a failed request, unit-tested. */
fun Throwable.toHistoryFailure(): HistoryResult<Nothing> {
    if (this is HttpException && (code() == 404 || code() == 405)) return HistoryResult.Unsupported
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return HistoryResult.Failed(offline = true)
        current = current.cause
    }
    return HistoryResult.Failed(offline = false)
}

/** Owner history of a project or yarn. Online-only: nothing is cached in Room. */
@Singleton
class HistoryRepository
@Inject
constructor(private val api: HistoryApi, private val capabilities: ApiCapabilities) {
    /** Email of the signed-in account, so the UI can omit "by you". Null before the v2 probe. */
    val currentEmail: String?
        get() = capabilities.current.user?.email

    suspend fun page(kind: HistoryKind, id: Int, page: Int): HistoryResult<AuditPageDto> = call {
        when (kind) {
            HistoryKind.Project -> api.projectHistory(id, page)
            HistoryKind.Yarn -> api.yarnHistory(id, page)
        }
    }

    suspend fun detail(kind: HistoryKind, id: Int, eventId: Long): HistoryResult<AuditDetailDto> =
        call {
            when (kind) {
                HistoryKind.Project -> api.projectHistoryEvent(id, eventId).data
                HistoryKind.Yarn -> api.yarnHistoryEvent(id, eventId).data
            }
        }

    private suspend fun <T> call(block: suspend () -> T): HistoryResult<T> =
        try {
            if (capabilities.resolve().generation != ApiGeneration.V2) {
                HistoryResult.Unsupported
            } else {
                HistoryResult.Ok(block())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.toHistoryFailure()
        }
}
