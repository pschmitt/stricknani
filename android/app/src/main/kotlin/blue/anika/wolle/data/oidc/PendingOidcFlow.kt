package blue.anika.wolle.data.oidc

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Which native flow a callback belongs to; the wire value is the callback's `flow` parameter. */
enum class OidcFlowType(val wire: String) {
    LOGIN("login"),
    LINK("link"),
    REAUTH("reauth");

    companion object {
        fun fromWire(value: String?): OidcFlowType? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The state of one native flow while the user is in the browser. Secrets ([codeVerifier],
 * [reauthToken]) are never printed by [toString].
 */
class PendingFlow
internal constructor(
    val type: OidcFlowType,
    val serverUrl: String,
    val provider: String,
    val appState: String,
    val codeVerifier: String,
    val expiresAtMillis: Long,
    /** Link flows: the `X-Reauth-Token` proof, sent again when the link is completed. */
    val reauthToken: String? = null,
    /** Reauth flows: what the proof being obtained is for. */
    val reauthAction: ReauthAction? = null,
    val reauthTarget: ReauthTarget? = null,
) {
    override fun toString() = "PendingFlow($type, $provider)"
}

/**
 * The single pending native OIDC flow, in memory only (never persisted, so a killed process just
 * means "that sign-in link expired, try again"). One flow at a time: [begin] replaces any earlier
 * one. [take] is the only way to read a flow back and consumes it, so a state can be used once.
 *
 * A callback is accepted only when its `state` equals the pending `app_state` (constant-time
 * compare) and its `flow` equals the pending type and the entry has not expired (10 minutes). A
 * callback that does not match the state is ignored without touching the pending flow, so a stale
 * or injected link cannot cancel a real sign-in in progress.
 */
@Singleton
class PendingOidcFlow internal constructor(private val clock: () -> Long) {
    @Inject constructor() : this(System::currentTimeMillis)

    private var current: PendingFlow? = null

    @Synchronized
    fun begin(
        type: OidcFlowType,
        serverUrl: String,
        provider: String,
        appState: String,
        codeVerifier: String,
        reauthToken: String? = null,
        reauthAction: ReauthAction? = null,
        reauthTarget: ReauthTarget? = null,
    ): PendingFlow =
        PendingFlow(
                type = type,
                serverUrl = serverUrl,
                provider = provider,
                appState = appState,
                codeVerifier = codeVerifier,
                expiresAtMillis = clock() + TTL_MILLIS,
                reauthToken = reauthToken,
                reauthAction = reauthAction,
                reauthTarget = reauthTarget,
            )
            .also { current = it }

    /**
     * Consumes the pending flow when [state] and [flow] match it and it has not expired; returns
     * null otherwise. A matching state is always cleared (even when the flow type or the expiry
     * then rejects it): that attempt is over. A non-matching state leaves the pending flow alone.
     * [flowOptional] accepts a callback without a usable `flow` (error callbacks may omit it).
     */
    @Synchronized
    fun take(state: String?, flow: OidcFlowType?, flowOptional: Boolean = false): PendingFlow? {
        val pending = current ?: return null
        if (state == null || !constantTimeEquals(pending.appState, state)) return null
        current = null
        if (clock() >= pending.expiresAtMillis) return null
        if (flow != pending.type && !(flowOptional && flow == null)) return null
        return pending
    }

    /** Drops the pending flow, e.g. when the start request itself failed. */
    @Synchronized
    fun clear() {
        current = null
    }

    @Synchronized fun hasPending(): Boolean = current?.let { clock() < it.expiresAtMillis } == true

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val TTL_MILLIS = 10 * 60 * 1000L
    }
}
