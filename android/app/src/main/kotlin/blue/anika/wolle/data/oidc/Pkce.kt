package blue.anika.wolle.data.oidc

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) and correlation-state generation for the native OIDC flows. Nothing here is
 * persisted: the verifier and state live in [PendingOidcFlow] only, in memory.
 */
object Pkce {
    /** RFC 7636 section 4.1: a verifier is 43 to 128 characters of `[A-Za-z0-9-._~]`. */
    const val MIN_VERIFIER_LENGTH = 43
    const val MAX_VERIFIER_LENGTH = 128

    /** 48 random bytes encode to exactly 64 base64url characters (no padding). */
    private const val VERIFIER_BYTES = 48

    /** 128 bits is the floor; 32 bytes gives 256 bits and a 43 character base64url string. */
    private const val STATE_BYTES = 32

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val verifierPattern = Regex("^[A-Za-z0-9._~-]{43,128}$")

    fun newCodeVerifier(random: SecureRandom = SecureRandom()): String =
        urlEncoder.encodeToString(ByteArray(VERIFIER_BYTES).also(random::nextBytes))

    /** A fresh `app_state`: at least 128 bits of randomness, base64url. */
    fun newAppState(random: SecureRandom = SecureRandom()): String =
        urlEncoder.encodeToString(ByteArray(STATE_BYTES).also(random::nextBytes))

    /** S256: `BASE64URL(SHA256(ASCII(verifier)))` without padding. */
    fun codeChallenge(verifier: String): String =
        urlEncoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

    fun isValidVerifier(verifier: String): Boolean = verifierPattern.matches(verifier)
}
