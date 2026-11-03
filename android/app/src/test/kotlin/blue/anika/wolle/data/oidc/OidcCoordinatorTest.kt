package blue.anika.wolle.data.oidc

import blue.anika.wolle.data.api.v2.V2Envelope
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSession(
    override var hasSession: Boolean = false,
    override var sessionServerUrl: String = "",
    override var savedAccountEmail: String? = null,
) : OidcSessionStore {
    val saved = mutableListOf<List<String?>>()
    val replaced = mutableListOf<List<String?>>()

    override fun saveNewSession(
        serverUrl: String,
        token: String,
        expiresAt: String?,
        authOrigin: String?,
    ) {
        saved += listOf(serverUrl, token, expiresAt, authOrigin)
        hasSession = true
        sessionServerUrl = serverUrl
    }

    override fun replaceToken(token: String, expiresAt: String?, authOrigin: String?) {
        replaced += listOf(token, expiresAt, authOrigin)
    }
}

private class FakeLoginApi : OidcLoginApi {
    var providers: List<OidcProvider> = listOf(OidcProvider("authelia", "Authelia"))
    var startError: Exception? = null
    var exchangeError: Exception? = null
    var email: String? = null
    val starts = mutableListOf<Pair<String, OidcStartRequest>>()
    val exchanges = mutableListOf<Pair<String, OidcLoginExchangeRequest>>()

    override suspend fun providers(serverUrl: String) = providers

    override suspend fun start(serverUrl: String, request: OidcStartRequest): OidcStartResponse {
        starts += serverUrl to request
        startError?.let { throw it }
        return OidcStartResponse("https://auth.example/authorize?x=1", "2026-11-03T12:00:00Z")
    }

    override suspend fun exchange(
        serverUrl: String,
        request: OidcLoginExchangeRequest,
    ): OidcLoginExchangeResponse {
        exchanges += serverUrl to request
        exchangeError?.let { throw it }
        return OidcLoginExchangeResponse("pat-new", "2026-11-10T12:00:00Z", "oidc")
    }

    override suspend fun currentUserEmail(serverUrl: String, token: String) = email
}

private class FakeAccountApi : OidcAccountApi {
    var error: Exception? = null
    val calls = mutableListOf<String>()
    val linkStarts = mutableListOf<Pair<String, OidcStartRequest>>()
    val linkExchanges = mutableListOf<Pair<String, OidcGrantExchangeRequest>>()
    val reauthStarts = mutableListOf<OidcReauthStartRequest>()
    val reauthExchanges = mutableListOf<OidcGrantExchangeRequest>()
    val passwordReauths = mutableListOf<PasswordReauthRequest>()
    val unlinks = mutableListOf<Triple<Long?, Long, String>>()
    val identity = OidcIdentity(7, "authelia", "id:abcd1234", null, "2026-11-03T12:00:00Z", true)

    private fun check(name: String) {
        calls += name
        error?.let { throw it }
    }

    override suspend fun identities(): IdentitiesEnvelope {
        check("identities")
        return IdentitiesEnvelope(listOf(identity), IdentitiesMeta(true))
    }

    override suspend fun reauthWithPassword(
        request: PasswordReauthRequest
    ): V2Envelope<ReauthTokenDto> {
        check("reauth")
        passwordReauths += request
        return V2Envelope(ReauthTokenDto("reauth-pw", "2026-11-03T12:10:00Z"))
    }

    override suspend fun reauthOidcStart(
        request: OidcReauthStartRequest
    ): V2Envelope<OidcStartResponse> {
        check("reauthStart")
        reauthStarts += request
        return V2Envelope(OidcStartResponse("https://auth.example/reauth"))
    }

    override suspend fun reauthOidcExchange(
        request: OidcGrantExchangeRequest
    ): V2Envelope<ReauthTokenDto> {
        check("reauthExchange")
        reauthExchanges += request
        return V2Envelope(ReauthTokenDto("reauth-oidc"))
    }

    override suspend fun linkStart(
        reauthToken: String,
        request: OidcStartRequest,
    ): V2Envelope<OidcStartResponse> {
        check("linkStart")
        linkStarts += reauthToken to request
        return V2Envelope(OidcStartResponse("https://auth.example/link"))
    }

    override suspend fun linkExchange(
        reauthToken: String,
        request: OidcGrantExchangeRequest,
    ): V2Envelope<LinkResultDto> {
        check("linkExchange")
        linkExchanges += reauthToken to request
        return V2Envelope(LinkResultDto(identity))
    }

    override suspend fun unlink(identityId: Long, reauthToken: String) {
        check("unlink")
        unlinks += Triple(null, identityId, reauthToken)
    }

    override suspend fun adminIdentities(userId: Long): IdentitiesEnvelope {
        check("adminIdentities")
        return IdentitiesEnvelope(listOf(identity))
    }

    override suspend fun adminUnlink(userId: Long, identityId: Long, reauthToken: String) {
        check("adminUnlink")
        unlinks += Triple(userId, identityId, reauthToken)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OidcCoordinatorTest {
    private val login = FakeLoginApi()
    private val account = FakeAccountApi()
    private val session = FakeSession()
    private val pending = PendingOidcFlow()
    private var syncs = 0
    private var stateCounter = 0

    private fun TestScope.coordinator() =
        OidcCoordinator(
            login,
            account,
            pending,
            session,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            afterSignIn = { syncs++ },
            tokenName = { "Android test" },
            newState = { "state-${++stateCounter}" },
            newVerifier = { "v".repeat(64) },
        )

    private fun cb(state: String, flow: String, code: String = "code-1") =
        "stricknani://oidc-callback?code=$code&state=$state&flow=$flow"

    @Test
    fun `login start sends the native fields and opens the authorization page`() = runTest {
        val c = coordinator()
        val urls = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            c.browserRequests.collect { urls += it }
        }
        assertNull(c.startLogin("https://s", "authelia"))
        assertEquals(listOf("https://auth.example/authorize?x=1"), urls)

        val (server, request) = login.starts.single()
        assertEquals("https://s", server)
        assertEquals("authelia", request.provider)
        assertEquals("android", request.client)
        assertEquals("stricknani://oidc-callback", request.redirectUri)
        assertEquals("state-1", request.appState)
        assertEquals("S256", request.codeChallengeMethod)
        assertEquals(Pkce.codeChallenge("v".repeat(64)), request.codeChallenge)
        assertTrue(pending.hasPending())
    }

    @Test
    fun `login callback exchanges the code and stores a new session`() = runTest {
        val c = coordinator()
        c.startLogin("https://s", "authelia")

        assertEquals(OidcCoordinator.CallbackResult.Accepted, c.onCallback(cb("state-1", "login")))

        val (server, ex) = login.exchanges.single()
        assertEquals("https://s", server)
        assertEquals("code-1", ex.code)
        assertEquals("v".repeat(64), ex.codeVerifier)
        assertEquals("stricknani://oidc-callback", ex.redirectUri)
        assertEquals("Android test", ex.tokenName)
        assertEquals(
            listOf("https://s", "pat-new", "2026-11-10T12:00:00Z", "oidc"),
            session.saved.single(),
        )
        assertEquals(1, syncs)
        val event = c.events.value as OidcEvent.SignedIn
        assertFalse(event.reSignIn)
        assertFalse(pending.hasPending())
    }

    @Test
    fun `the same callback cannot be used twice`() = runTest {
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        c.onCallback(cb("state-1", "login"))
        assertEquals(OidcCoordinator.CallbackResult.Rejected, c.onCallback(cb("state-1", "login")))
        assertEquals(1, login.exchanges.size)
    }

    @Test
    fun `a callback with an unknown state is rejected without any exchange`() = runTest {
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        assertEquals(OidcCoordinator.CallbackResult.Rejected, c.onCallback(cb("forged", "login")))
        assertTrue(login.exchanges.isEmpty())
        assertTrue(session.saved.isEmpty())
        // and the real sign-in can still finish
        assertEquals(OidcCoordinator.CallbackResult.Accepted, c.onCallback(cb("state-1", "login")))
    }

    @Test
    fun `a callback with no pending flow is rejected`() = runTest {
        val c = coordinator()
        assertEquals(OidcCoordinator.CallbackResult.Rejected, c.onCallback(cb("state-1", "login")))
        assertTrue(login.exchanges.isEmpty())
    }

    @Test
    fun `a wrong flow is rejected`() = runTest {
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        assertEquals(OidcCoordinator.CallbackResult.Rejected, c.onCallback(cb("state-1", "link")))
        assertTrue(login.exchanges.isEmpty())
    }

    @Test
    fun `other URIs are not callbacks`() = runTest {
        val c = coordinator()
        assertEquals(
            OidcCoordinator.CallbackResult.NotACallback,
            c.onCallback("stricknani://setup?p=abc"),
        )
        assertEquals(
            OidcCoordinator.CallbackResult.NotACallback,
            c.onCallback("https://x/projects/1"),
        )
    }

    @Test
    fun `a provider error callback fails the flow and clears it`() = runTest {
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        assertEquals(
            OidcCoordinator.CallbackResult.Accepted,
            c.onCallback("stricknani://oidc-callback?state=state-1&error=oidc_failed"),
        )
        val event = c.events.value as OidcEvent.Failed
        assertEquals(OidcFlowType.LOGIN, event.flow)
        assertEquals(OidcFailure.ProviderError, event.failure)
        assertTrue(login.exchanges.isEmpty())
        assertFalse(pending.hasPending())
    }

    @Test
    fun `an unlinked identity is reported and nothing is stored`() = runTest {
        login.exchangeError = OidcHttpException(403, "oidc_identity_unlinked", null)
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        c.onCallback(cb("state-1", "login"))
        assertEquals(OidcFailure.Unlinked, (c.events.value as OidcEvent.Failed).failure)
        assertTrue(session.saved.isEmpty())
        assertFalse(pending.hasPending())
        assertEquals(0, syncs)
    }

    @Test
    fun `rate limited exchange keeps Retry-After`() = runTest {
        login.exchangeError = OidcHttpException(429, "rate_limited", 42)
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        c.onCallback(cb("state-1", "login"))
        assertEquals(OidcFailure.RateLimited(42), (c.events.value as OidcEvent.Failed).failure)
    }

    @Test
    fun `offline exchange is reported as offline`() = runTest {
        login.exchangeError = IOException("no route")
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        c.onCallback(cb("state-1", "login"))
        assertEquals(OidcFailure.Offline, (c.events.value as OidcEvent.Failed).failure)
    }

    @Test
    fun `a failed start returns the failure and leaves nothing pending`() = runTest {
        login.startError = OidcHttpException(503, "oidc_unavailable", null)
        val c = coordinator()
        assertEquals(OidcFailure.Unavailable, c.startLogin("https://s", "authelia"))
        assertFalse(pending.hasPending())
    }

    @Test
    fun `re-sign-in replaces only the token for the same account`() = runTest {
        session.hasSession = true
        session.sessionServerUrl = "https://s/"
        session.savedAccountEmail = "Me@Example.org"
        login.email = "me@example.org"
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        c.onCallback(cb("state-1", "login"))

        assertTrue(session.saved.isEmpty())
        assertEquals(listOf("pat-new", "2026-11-10T12:00:00Z", "oidc"), session.replaced.single())
        assertEquals(1, syncs)
        assertTrue((c.events.value as OidcEvent.SignedIn).reSignIn)
    }

    @Test
    fun `re-sign-in as a different account is refused and keeps the old credentials`() = runTest {
        session.hasSession = true
        session.sessionServerUrl = "https://s"
        session.savedAccountEmail = "me@example.org"
        login.email = "someone-else@example.org"
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        c.onCallback(cb("state-1", "login"))

        assertTrue(session.replaced.isEmpty())
        assertTrue(session.saved.isEmpty())
        assertEquals(OidcFailure.AccountMismatch, (c.events.value as OidcEvent.Failed).failure)
        assertEquals(0, syncs)
    }

    @Test
    fun `link start needs a link proof and sends it as the header value`() = runTest {
        session.hasSession = true
        session.sessionServerUrl = "https://s"
        val c = coordinator()
        val wrong = ReauthProof(ReauthAction.UNLINK_IDENTITY, ReauthTarget.Identity(1), "t")
        assertEquals(OidcFailure.ReauthRequired, c.startLink("authelia", wrong))
        assertTrue(account.linkStarts.isEmpty())

        val proof =
            ReauthProof(ReauthAction.LINK_IDENTITY, ReauthTarget.Provider("authelia"), "proof-1")
        assertNull(c.startLink("authelia", proof))
        val (header, body) = account.linkStarts.single()
        assertEquals("proof-1", header)
        assertEquals("authelia", body.provider)
        assertEquals("android", body.client)
        assertEquals("stricknani://oidc-callback", body.redirectUri)
    }

    @Test
    fun `link callback completes with the same proof and reports the identity`() = runTest {
        session.hasSession = true
        session.sessionServerUrl = "https://s"
        val c = coordinator()
        val proof =
            ReauthProof(ReauthAction.LINK_IDENTITY, ReauthTarget.Provider("authelia"), "proof-1")
        c.startLink("authelia", proof)
        c.onCallback(cb("state-1", "link"))

        val (header, ex) = account.linkExchanges.single()
        assertEquals("proof-1", header)
        assertEquals("code-1", ex.code)
        assertEquals("v".repeat(64), ex.codeVerifier)
        assertEquals(account.identity, (c.events.value as OidcEvent.Linked).identity)
        // a link never mints or stores a token
        assertTrue(session.saved.isEmpty())
        assertTrue(session.replaced.isEmpty())
    }

    @Test
    fun `an already linked identity is reported`() = runTest {
        session.hasSession = true
        session.sessionServerUrl = "https://s"
        account.error = null
        val c = coordinator()
        c.startLink(
            "authelia",
            ReauthProof(ReauthAction.LINK_IDENTITY, ReauthTarget.Provider("authelia"), "p"),
        )
        account.error = retrofitHttp(409, "identity_already_linked")
        c.onCallback(cb("state-1", "link"))
        assertEquals(OidcFailure.AlreadyLinked, (c.events.value as OidcEvent.Failed).failure)
    }

    @Test
    fun `reauth through the provider yields a bound proof`() = runTest {
        session.hasSession = true
        session.sessionServerUrl = "https://s"
        val c = coordinator()
        val target = ReauthTarget.Identity(7)
        assertNull(c.startReauth("authelia", ReauthAction.UNLINK_IDENTITY, target))
        val start = account.reauthStarts.single()
        assertEquals("unlink_identity", start.action)
        assertEquals(7L, start.target?.identityId)
        assertNull(start.target?.provider)

        c.onCallback(cb("state-1", "reauth"))
        val proof = (c.events.value as OidcEvent.Reauthenticated).proof
        assertEquals("reauth-oidc", proof.token)
        assertEquals(ReauthAction.UNLINK_IDENTITY, proof.action)
        assertEquals(target, proof.target)
        assertFalse(proof.toString().contains("reauth-oidc"))
    }

    @Test
    fun `password reauth sends the action and target and returns a proof`() = runTest {
        val c = coordinator()
        val result =
            c.reauthWithPassword(
                "hunter2",
                ReauthAction.ADMIN_UNLINK_IDENTITY,
                ReauthTarget.AdminIdentity(userId = 5, identityId = 9),
            )
        assertEquals("reauth-pw", result.getOrThrow().token)
        val sent = account.passwordReauths.single()
        assertEquals("admin_unlink_identity", sent.action)
        assertEquals(5L, sent.target?.userId)
        assertEquals(9L, sent.target?.identityId)
    }

    @Test
    fun `a wrong password is reported as such`() = runTest {
        account.error = retrofitHttp(401, "invalid_credentials")
        val c = coordinator()
        val result =
            c.reauthWithPassword(
                "nope",
                ReauthAction.LINK_IDENTITY,
                ReauthTarget.Provider("authelia"),
            )
        assertEquals(OidcFailure.InvalidPassword, result.oidcFailure())
    }

    @Test
    fun `unlink needs the unlink proof and maps the last-method conflict`() = runTest {
        val c = coordinator()
        val ok = ReauthProof(ReauthAction.UNLINK_IDENTITY, ReauthTarget.Identity(7), "p")
        assertNull(c.unlink(7, ok))
        assertEquals(Triple(null, 7L, "p"), account.unlinks.single())

        assertEquals(
            OidcFailure.ReauthRequired,
            c.unlink(7, ReauthProof(ReauthAction.LINK_IDENTITY, ReauthTarget.Provider("a"), "p")),
        )
        account.error = retrofitHttp(409, "last_login_method")
        assertEquals(OidcFailure.LastLoginMethod, c.unlink(7, ok))
    }

    @Test
    fun `admin unlink needs the admin proof`() = runTest {
        val c = coordinator()
        val proof =
            ReauthProof(ReauthAction.ADMIN_UNLINK_IDENTITY, ReauthTarget.AdminIdentity(5, 9), "p")
        assertNull(c.adminUnlink(5, 9, proof))
        assertEquals(Triple(5L, 9L, "p"), account.unlinks.single())
        assertEquals(
            OidcFailure.ReauthRequired,
            c.adminUnlink(
                5,
                9,
                ReauthProof(ReauthAction.UNLINK_IDENTITY, ReauthTarget.Identity(9), "p"),
            ),
        )
    }

    @Test
    fun `identities load through the authenticated api`() = runTest {
        val c = coordinator()
        val envelope = c.loadIdentities().getOrThrow()
        assertEquals(listOf(account.identity), envelope.data)
        assertTrue(envelope.meta.passwordEnabled)
        account.error = retrofitHttp(401, "session_expired")
        assertEquals(OidcFailure.SessionExpired, c.loadIdentities().oidcFailure())
    }

    @Test
    fun `consume clears only the same event`() = runTest {
        val c = coordinator()
        c.startLogin("https://s", "authelia")
        c.onCallback(cb("state-1", "login"))
        val event = c.events.value!!
        c.consume(OidcEvent.SignedIn(event.id + 100, false))
        assertNotNull(c.events.value)
        c.consume(event)
        assertNull(c.events.value)
    }

    @Test
    fun `providers come from the login api`() = runTest {
        login.providers = emptyList()
        assertTrue(coordinator().providers("https://s").isEmpty())
    }
}
