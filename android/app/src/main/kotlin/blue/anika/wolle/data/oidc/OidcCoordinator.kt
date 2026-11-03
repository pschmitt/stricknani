package blue.anika.wolle.data.oidc

import blue.anika.wolle.data.device.DeviceNameProvider
import blue.anika.wolle.data.device.deviceTokenName
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.sync.SyncScheduler
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** The outcome of a native flow, delivered once through [OidcCoordinator.events]. */
sealed interface OidcEvent {
    val id: Long

    /** A login finished and the token is stored. [reSignIn]: it replaced an expired one. */
    data class SignedIn(override val id: Long, val reSignIn: Boolean) : OidcEvent

    data class Linked(override val id: Long, val identity: OidcIdentity) : OidcEvent

    /** A reauthentication through the provider finished; [proof] is for its action and target. */
    data class Reauthenticated(override val id: Long, val proof: ReauthProof) : OidcEvent

    data class Failed(override val id: Long, val flow: OidcFlowType, val failure: OidcFailure) :
        OidcEvent
}

/**
 * Drives the three native OIDC flows (login, link, reauthentication) end to end, per
 * `docs/oidc-v2-contract.md` and the Android section of `docs/oidc-plan.md`:
 * 1. a start call hands back the provider's authorization URL, emitted on [browserRequests] for the
 *    activity to open in a Custom Tab (the PKCE verifier and `app_state` wait in [PendingOidcFlow],
 *    in memory only);
 * 2. [onCallback] receives `stricknani://oidc-callback?...`, accepts it only for a matching pending
 *    state and flow, then exchanges the one-time code;
 * 3. the result is published on [events].
 *
 * Tokens and codes never go into URLs, logs or exceptions here.
 */
@Singleton
class OidcCoordinator
internal constructor(
    private val loginApi: OidcLoginApi,
    private val accountApi: OidcAccountApi,
    private val pending: PendingOidcFlow,
    private val session: OidcSessionStore,
    private val scope: CoroutineScope,
    private val afterSignIn: () -> Unit,
    private val tokenName: () -> String,
    private val deviceName: () -> String? = { null },
    private val newState: () -> String = { Pkce.newAppState() },
    private val newVerifier: () -> String = { Pkce.newCodeVerifier() },
) {
    @Inject
    constructor(
        loginApi: OidcLoginApi,
        accountApi: OidcAccountApi,
        pending: PendingOidcFlow,
        settings: SettingsRepository,
        syncScheduler: SyncScheduler,
        deviceNames: DeviceNameProvider,
    ) : this(
        loginApi,
        accountApi,
        pending,
        settings,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { syncScheduler.replayThenSyncNow() },
        { deviceTokenName(deviceNames.deviceName()) },
        { deviceNames.deviceName() },
    )

    private val _browserRequests =
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Authorization URLs for the activity to open in a Custom Tab. */
    val browserRequests: SharedFlow<String> = _browserRequests.asSharedFlow()

    private val _events = MutableStateFlow<OidcEvent?>(null)

    /** The latest unhandled result; the screen that handled it calls [consume]. */
    val events: StateFlow<OidcEvent?> = _events.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** True while a one-time code is being exchanged (after the browser returned). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var nextEventId = 1L

    fun consume(event: OidcEvent) {
        _events.compareAndSet(event, null)
    }

    suspend fun providers(serverUrl: String): List<OidcProvider> = loginApi.providers(serverUrl)

    // ---- starting flows --------------------------------------------------------------------

    /** Login (or re-sign-in with the saved server): null on success, the failure otherwise. */
    suspend fun startLogin(serverUrl: String, provider: String): OidcFailure? =
        start(OidcFlowType.LOGIN, serverUrl, provider) { state, verifier ->
            loginApi
                .start(
                    serverUrl,
                    OidcStartRequest(
                        provider = provider,
                        client = OIDC_CLIENT_ANDROID,
                        redirectUri = OIDC_REDIRECT_URI,
                        appState = state,
                        codeChallenge = Pkce.codeChallenge(verifier),
                        codeChallengeMethod = OIDC_CHALLENGE_METHOD,
                    ),
                )
                .authorizationUrl
        }

    /** Starts linking [provider] to the signed-in account, with a `link_identity` [proof]. */
    suspend fun startLink(provider: String, proof: ReauthProof): OidcFailure? {
        if (proof.action != ReauthAction.LINK_IDENTITY) return OidcFailure.ReauthRequired
        return start(
            OidcFlowType.LINK,
            session.sessionServerUrl,
            provider,
            reauthToken = proof.token,
        ) { state, verifier ->
            accountApi
                .linkStart(
                    proof.token,
                    OidcStartRequest(
                        provider = provider,
                        client = OIDC_CLIENT_ANDROID,
                        redirectUri = OIDC_REDIRECT_URI,
                        appState = state,
                        codeChallenge = Pkce.codeChallenge(verifier),
                        codeChallengeMethod = OIDC_CHALLENGE_METHOD,
                    ),
                )
                .data
                .authorizationUrl
        }
    }

    /** Starts a reauthentication through the provider for [action] and [target]. */
    suspend fun startReauth(
        provider: String,
        action: ReauthAction,
        target: ReauthTarget,
    ): OidcFailure? =
        start(
            OidcFlowType.REAUTH,
            session.sessionServerUrl,
            provider,
            reauthAction = action,
            reauthTarget = target,
        ) { state, verifier ->
            accountApi
                .reauthOidcStart(
                    OidcReauthStartRequest(
                        provider = provider,
                        action = action.wire,
                        target = target.toDto(),
                        client = OIDC_CLIENT_ANDROID,
                        redirectUri = OIDC_REDIRECT_URI,
                        appState = state,
                        codeChallenge = Pkce.codeChallenge(verifier),
                        codeChallengeMethod = OIDC_CHALLENGE_METHOD,
                    )
                )
                .data
                .authorizationUrl
        }

    private suspend fun start(
        type: OidcFlowType,
        serverUrl: String,
        provider: String,
        reauthToken: String? = null,
        reauthAction: ReauthAction? = null,
        reauthTarget: ReauthTarget? = null,
        request: suspend (state: String, verifier: String) -> String,
    ): OidcFailure? {
        val state = newState()
        val verifier = newVerifier()
        // Registered before the call so the flow exists whatever the browser does next; dropped
        // again if the server refuses to start it.
        pending.begin(
            type,
            serverUrl,
            provider,
            state,
            verifier,
            reauthToken,
            reauthAction,
            reauthTarget,
        )
        return try {
            val url = request(state, verifier)
            if (!_browserRequests.tryEmit(url)) {
                pending.clear()
                OidcFailure.Other(null)
            } else null
        } catch (cancellation: CancellationException) {
            pending.clear()
            throw cancellation
        } catch (failure: Exception) {
            pending.clear()
            failure.toOidcFailure()
        }
    }

    /** `POST /account/reauth` with the explicit PAT: the proof stays in memory with the caller. */
    suspend fun reauthWithPassword(
        password: String,
        action: ReauthAction,
        target: ReauthTarget,
    ): Result<ReauthProof> =
        try {
            val token =
                accountApi
                    .reauthWithPassword(
                        PasswordReauthRequest(password, action.wire, target.toDto())
                    )
                    .data
                    .reauthToken
            Result.success(ReauthProof(action, target, token))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(OidcFailureException(failure.toOidcFailure()))
        }

    // ---- linked identities -----------------------------------------------------------------

    suspend fun loadIdentities(): Result<IdentitiesEnvelope> = call { accountApi.identities() }

    suspend fun loadAdminIdentities(userId: Long): Result<IdentitiesEnvelope> = call {
        accountApi.adminIdentities(userId)
    }

    /** `DELETE /account/identities/{id}` with an `unlink_identity` proof; null on success. */
    suspend fun unlink(identityId: Long, proof: ReauthProof): OidcFailure? {
        if (proof.action != ReauthAction.UNLINK_IDENTITY) return OidcFailure.ReauthRequired
        return call { accountApi.unlink(identityId, proof.token) }.oidcFailure()
    }

    /** Administrator unlink with an `admin_unlink_identity` proof; null on success. */
    suspend fun adminUnlink(userId: Long, identityId: Long, proof: ReauthProof): OidcFailure? {
        if (proof.action != ReauthAction.ADMIN_UNLINK_IDENTITY) return OidcFailure.ReauthRequired
        return call { accountApi.adminUnlink(userId, identityId, proof.token) }.oidcFailure()
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(OidcFailureException(failure.toOidcFailure()))
        }

    // ---- the callback ----------------------------------------------------------------------

    /**
     * Handles an incoming `stricknani://oidc-callback` URI. Returns false when [uri] is not an OIDC
     * callback at all (so the activity can try its other deep links), true when it was one, whether
     * it was accepted or rejected. A callback that matches no pending flow is ignored
     * ([CallbackResult.Rejected]) and the caller tells the user the link expired.
     */
    fun onCallback(uri: String?): CallbackResult {
        val callback = OidcCallback.parse(uri) ?: return CallbackResult.NotACallback
        // An error callback may omit `flow`; a state match is what proves it is ours.
        val taken =
            pending.take(callback.state, callback.flowType, flowOptional = callback.error != null)
                ?: return CallbackResult.Rejected
        if (callback.error != null) {
            publish { OidcEvent.Failed(it, taken.type, OidcFailure.ProviderError) }
            return CallbackResult.Accepted
        }
        val code = callback.code
        if (code == null) {
            publish { OidcEvent.Failed(it, taken.type, OidcFailure.Expired) }
            return CallbackResult.Accepted
        }
        _busy.value = true
        scope.launch {
            try {
                exchange(taken, code)
            } finally {
                _busy.value = false
            }
        }
        return CallbackResult.Accepted
    }

    enum class CallbackResult {
        NotACallback,
        /** An OIDC callback with no matching pending flow: show "that link expired". */
        Rejected,
        Accepted,
    }

    private suspend fun exchange(flow: PendingFlow, code: String) {
        try {
            when (flow.type) {
                OidcFlowType.LOGIN -> exchangeLogin(flow, code)
                OidcFlowType.LINK -> exchangeLink(flow, code)
                OidcFlowType.REAUTH -> exchangeReauth(flow, code)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure !is IOException && failure !is OidcFailureException) {
                Timber.w("OIDC ${flow.type} exchange failed: ${failure.javaClass.simpleName}")
            }
            publish { OidcEvent.Failed(it, flow.type, failure.toOidcFailure()) }
        }
    }

    private suspend fun exchangeLogin(flow: PendingFlow, code: String) {
        val result =
            loginApi.exchange(
                flow.serverUrl,
                OidcLoginExchangeRequest(
                    code = code,
                    codeVerifier = flow.codeVerifier,
                    redirectUri = OIDC_REDIRECT_URI,
                    tokenName = tokenName(),
                    deviceName = deviceName(),
                ),
            )
        val reSignIn = session.hasSession && sameServer(session.sessionServerUrl, flow.serverUrl)
        if (reSignIn) {
            // Same device, same cache: only continue if the token belongs to the same account, so
            // queued writes never reach someone else's account.
            val saved = session.savedAccountEmail
            if (saved != null) {
                val actual = loginApi.currentUserEmail(flow.serverUrl, result.token)
                if (actual != null && !actual.equals(saved, ignoreCase = true)) {
                    publish { OidcEvent.Failed(it, flow.type, OidcFailure.AccountMismatch) }
                    return
                }
            }
            session.replaceToken(
                result.token,
                result.expiresAt,
                result.authOrigin ?: AUTH_ORIGIN_OIDC,
            )
            afterSignIn()
        } else {
            session.saveNewSession(
                flow.serverUrl,
                result.token,
                result.expiresAt,
                result.authOrigin ?: AUTH_ORIGIN_OIDC,
            )
            afterSignIn()
        }
        publish { OidcEvent.SignedIn(it, reSignIn) }
    }

    private suspend fun exchangeLink(flow: PendingFlow, code: String) {
        val proof = flow.reauthToken
        if (
            proof == null ||
                !session.hasSession ||
                !sameServer(session.sessionServerUrl, flow.serverUrl)
        ) {
            throw OidcFailureException(OidcFailure.Expired)
        }
        val identity =
            accountApi
                .linkExchange(
                    proof,
                    OidcGrantExchangeRequest(code, flow.codeVerifier, OIDC_REDIRECT_URI),
                )
                .data
                .identity
        publish { OidcEvent.Linked(it, identity) }
    }

    private suspend fun exchangeReauth(flow: PendingFlow, code: String) {
        val action = flow.reauthAction
        val target = flow.reauthTarget
        if (action == null || target == null || !session.hasSession) {
            throw OidcFailureException(OidcFailure.Expired)
        }
        val token =
            accountApi
                .reauthOidcExchange(
                    OidcGrantExchangeRequest(code, flow.codeVerifier, OIDC_REDIRECT_URI)
                )
                .data
                .reauthToken
        publish { OidcEvent.Reauthenticated(it, ReauthProof(action, target, token)) }
    }

    @Synchronized
    private fun publish(build: (Long) -> OidcEvent) {
        _events.value = build(nextEventId++)
    }

    private fun sameServer(a: String, b: String) =
        a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
}
