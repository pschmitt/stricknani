package blue.anika.wolle.data.oidc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOidcFlowTest {
    private var now = 1_000_000L
    private val pending = PendingOidcFlow { now }

    private fun begin(type: OidcFlowType = OidcFlowType.LOGIN, state: String = "state-1") =
        pending.begin(type, "https://s", "authelia", state, "verifier-xyz")

    @Test
    fun `a matching state and flow is accepted`() {
        begin()
        val taken = pending.take("state-1", OidcFlowType.LOGIN)
        assertNotNull(taken)
        assertEquals("verifier-xyz", taken!!.codeVerifier)
        assertEquals("https://s", taken.serverUrl)
    }

    @Test
    fun `a state can be used only once`() {
        begin()
        assertNotNull(pending.take("state-1", OidcFlowType.LOGIN))
        assertNull(pending.take("state-1", OidcFlowType.LOGIN))
        assertFalse(pending.hasPending())
    }

    @Test
    fun `an unknown state is ignored and keeps the real flow alive`() {
        begin()
        assertNull(pending.take("injected", OidcFlowType.LOGIN))
        assertNull(pending.take(null, OidcFlowType.LOGIN))
        assertTrue(pending.hasPending())
        assertNotNull(pending.take("state-1", OidcFlowType.LOGIN))
    }

    @Test
    fun `nothing pending rejects everything`() {
        assertNull(pending.take("state-1", OidcFlowType.LOGIN))
    }

    @Test
    fun `a flow mismatch is rejected and ends that attempt`() {
        begin(OidcFlowType.LOGIN)
        assertNull(pending.take("state-1", OidcFlowType.LINK))
        // The state was used by that attempt: the right flow cannot reuse it.
        assertNull(pending.take("state-1", OidcFlowType.LOGIN))
    }

    @Test
    fun `a missing flow is rejected unless the callback is an error one`() {
        begin()
        assertNull(pending.take("state-1", null))
        begin()
        assertNotNull(pending.take("state-1", null, flowOptional = true))
    }

    @Test
    fun `an error callback with a wrong flow is still rejected`() {
        begin(OidcFlowType.LINK)
        assertNull(pending.take("state-1", OidcFlowType.LOGIN, flowOptional = true))
    }

    @Test
    fun `a flow expires after ten minutes`() {
        begin()
        now += PendingOidcFlow.TTL_MILLIS - 1
        assertTrue(pending.hasPending())
        now += 1
        assertFalse(pending.hasPending())
        assertNull(pending.take("state-1", OidcFlowType.LOGIN))
    }

    @Test
    fun `an expired flow with the right state is consumed`() {
        begin()
        now += PendingOidcFlow.TTL_MILLIS + 1
        assertNull(pending.take("state-1", OidcFlowType.LOGIN))
        assertNull(pending.take("state-1", OidcFlowType.LOGIN))
    }

    @Test
    fun `a new flow replaces the pending one`() {
        begin(state = "old")
        begin(state = "new")
        assertNull(pending.take("old", OidcFlowType.LOGIN))
        assertNotNull(pending.take("new", OidcFlowType.LOGIN))
    }

    @Test
    fun `clear drops the flow`() {
        begin()
        pending.clear()
        assertNull(pending.take("state-1", OidcFlowType.LOGIN))
    }

    @Test
    fun `link and reauth context is carried and secrets are not printed`() {
        val flow =
            pending.begin(
                OidcFlowType.LINK,
                "https://s",
                "authelia",
                "st",
                "the-verifier",
                reauthToken = "proof-token",
                reauthAction = ReauthAction.LINK_IDENTITY,
                reauthTarget = ReauthTarget.Provider("authelia"),
            )
        assertEquals("proof-token", flow.reauthToken)
        assertFalse(flow.toString().contains("the-verifier"))
        assertFalse(flow.toString().contains("proof-token"))
        assertFalse(flow.toString().contains("st,"))
    }
}
