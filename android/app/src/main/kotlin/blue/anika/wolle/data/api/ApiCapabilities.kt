package blue.anika.wolle.data.api

import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.oidc.errorCodeOf
import blue.anika.wolle.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
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
data class CurrentUser(
    val id: Long,
    val email: String,
    val isAdmin: Boolean,
    val avatarUrl: String? = null,
)

/** [user] is only known when [generation] is [ApiGeneration.V2]. */
data class ApiCapabilitiesState(
    val generation: ApiGeneration = ApiGeneration.V1,
    val user: CurrentUser? = null,
    /** The id of the token this app signs in with (`meta.current_token_id`), when reported. */
    val currentTokenId: Long? = null,
)

/**
 * Decides, per server and token, whether resource calls go to `/api/v2` or `/api/v1`.
 *
 * One probe of `GET /api/v2/meta` with the Bearer token, cached for the rest of the process and
 * keyed by server URL + token (a sign-out or token change clears it):
 * - 200 with a `user` -> V2, and the body also tells us the user's id, email and admin flag;
 * - 404/405 (a server without v2), a 401/403 without a v2 error body (a legacy server), or 200
 *   without a user -> V1, cached.
 *
 * Only a server that lacks v2 selects v1. Any other failure (a v2 401 `session_expired`, 403, 5xx,
 * no connection) is thrown to the caller as that call's normal failure, so offline handling, the
 * outbox and the expired-token handling behave as before, and it is not cached: the next call
 * probes again.
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

    /**
     * The generation to use for the next resource call. Probes at most once per credentials; throws
     * the probe's failure (offline, 401, 5xx) instead of guessing v1.
     */
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

    private suspend fun probe(key: String): ApiCapabilitiesState =
        try {
            val meta = metaApi.getMeta().data
            val user = meta.user
            remember(
                key,
                if (user == null) ApiCapabilitiesState()
                else
                    ApiCapabilitiesState(
                        ApiGeneration.V2,
                        CurrentUser(user.id, user.email, user.isAdmin, user.avatarUrl),
                        meta.currentTokenId,
                    ),
            )
        } catch (failure: HttpException) {
            if (isNoV2Server(failure)) remember(key, ApiCapabilitiesState())
            else throw undecided(failure)
        } catch (failure: Exception) {
            throw undecided(failure)
        }

    /**
     * A server without v2: 404/405 (no route), or a 401/403 that is not a v2 error envelope. A v2
     * server always answers `{"error": {"code": ...}}`; a legacy server whose auth runs before the
     * router answers 401 on unknown routes with its own body, which must not read as a rejected
     * token (the v1 calls then decide that on their own).
     */
    private fun isNoV2Server(failure: HttpException): Boolean =
        when (failure.code()) {
            404,
            405 -> true
            401,
            403 ->
                errorCodeOf(
                    runCatching { failure.response()?.errorBody()?.string() }.getOrNull().orEmpty()
                ) == null
            else -> false
        }

    private fun remember(key: String, result: ApiCapabilitiesState): ApiCapabilitiesState {
        probedKey = key
        _state.value = result
        Timber.i("API generation for this server: ${result.generation}")
        return result
    }

    /** The probe could not decide: forget any stale answer and let the caller see the failure. */
    private fun undecided(failure: Exception): Exception {
        probedKey = null
        _state.value = ApiCapabilitiesState()
        return failure
    }
}
