package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import timber.log.Timber

/** Which API generation the app talks to for resource calls (SNA-87). */
enum class ApiGeneration {
    V1,
    V2,
}

/** The signed-in account as reported by `GET /api/v2/meta`. */
data class CurrentUser(val id: Long, val email: String, val isAdmin: Boolean)

/** [user] is only known when [generation] is [ApiGeneration.V2]. */
data class ApiCapabilitiesState(
    val generation: ApiGeneration = ApiGeneration.V1,
    val user: CurrentUser? = null,
)

/**
 * Decides, per server and token, whether resource calls go to `/api/v2` or `/api/v1`.
 *
 * One probe of `GET /api/v2/meta` with the Bearer token, cached for the rest of the process (and
 * re-run when the server URL or token changes):
 * - 200 with a `user` -> V2, and the body also tells us the user's id, email and admin flag;
 * - 404/405 (an older server), or 200 without a user -> V1, cached;
 * - 401/403, a network error or a 5xx -> V1 for this call only, not cached, so the next call asks
 *   again. v1 stays a safe fallback because the old routes still exist on every server that has v2.
 *
 * Never defaults to V2 when unknown.
 */
@Singleton
class ApiCapabilities
internal constructor(private val metaApi: MetaV2Api, private val credentialKey: () -> String) {
    @Inject
    constructor(
        metaApi: MetaV2Api,
        settings: SettingsRepository,
    ) : this(
        metaApi,
        {
            val credentials = settings.credentials.value
            if (credentials.isValid) "${credentials.serverUrl}\n${credentials.apiToken}" else ""
        },
    )

    private val _state = MutableStateFlow(ApiCapabilitiesState())
    val state: StateFlow<ApiCapabilitiesState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var probedKey: String? = null

    /** Last known state without probing; V1 until [resolve] ran for the current credentials. */
    val current: ApiCapabilitiesState
        get() = if (probedKey == credentialKey()) _state.value else ApiCapabilitiesState()

    /** The generation to use for the next resource call. Probes at most once per credentials. */
    suspend fun generation(): ApiGeneration = resolve().generation

    suspend fun useV2(): Boolean = generation() == ApiGeneration.V2

    suspend fun resolve(): ApiCapabilitiesState {
        val key = credentialKey()
        if (key.isEmpty()) {
            reset()
            return _state.value
        }
        if (probedKey == key) return _state.value
        return mutex.withLock {
            val lockedKey = credentialKey()
            if (lockedKey.isEmpty()) {
                reset()
                return@withLock _state.value
            }
            if (probedKey == lockedKey) return@withLock _state.value
            probe(lockedKey)
        }
    }

    /** Forget the cached result so the next call probes again (for example after a v2 401). */
    fun invalidate() {
        probedKey = null
    }

    private fun reset() {
        probedKey = null
        _state.value = ApiCapabilitiesState()
    }

    private suspend fun probe(key: String): ApiCapabilitiesState {
        return try {
            val meta = metaApi.getMeta().data
            val user = meta.user
            val result =
                if (user == null) ApiCapabilitiesState()
                else
                    ApiCapabilitiesState(
                        ApiGeneration.V2,
                        CurrentUser(user.id, user.email, user.isAdmin),
                    )
            remember(key, result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: HttpException) {
            when (failure.code()) {
                404,
                405 -> remember(key, ApiCapabilitiesState())
                else -> unknown(failure)
            }
        } catch (failure: Exception) {
            unknown(failure)
        }
    }

    private fun remember(key: String, result: ApiCapabilitiesState): ApiCapabilitiesState {
        probedKey = key
        _state.value = result
        Timber.i("API generation for this server: ${result.generation}")
        return result
    }

    /** The probe could not decide: use v1 now and ask again next time. */
    private fun unknown(failure: Exception): ApiCapabilitiesState {
        Timber.d(failure, "API capability probe failed; using v1 for now")
        probedKey = null
        _state.value = ApiCapabilitiesState()
        return _state.value
    }
}
