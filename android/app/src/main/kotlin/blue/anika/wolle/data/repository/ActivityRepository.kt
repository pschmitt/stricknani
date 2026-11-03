package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ApiGeneration
import blue.anika.wolle.data.api.dto.ActivityPageDto
import blue.anika.wolle.data.api.v2.ActivityV2Api
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The Home "Recent activity" feed. Online-only: nothing is cached in Room. Failures are mapped like
 * the history ones ([toHistoryFailure]), so a server without the endpoint is
 * [HistoryResult.Unsupported].
 */
@Singleton
class ActivityRepository
@Inject
constructor(private val api: ActivityV2Api, private val capabilities: ApiCapabilities) {
    suspend fun page(limit: Int, before: Long?): HistoryResult<ActivityPageDto> =
        try {
            if (capabilities.resolve().generation != ApiGeneration.V2) {
                HistoryResult.Unsupported
            } else {
                HistoryResult.Ok(api.activity(limit, before))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.toHistoryFailure()
        }
}
