package blue.anika.wolle.ui.common

/** Public privacy policy, also the Google Play listing URL. Used when no server is configured. */
const val DEFAULT_PRIVACY_POLICY_URL = "https://wolle.anika.blue/privacy"

/**
 * The privacy policy served by the configured Stricknani server, or [DEFAULT_PRIVACY_POLICY_URL]
 * while no server is configured (for example during onboarding).
 */
fun privacyPolicyUrl(serverUrl: String): String {
    val base = serverUrl.trim().trimEnd('/')
    return if (base.isEmpty()) DEFAULT_PRIVACY_POLICY_URL else "$base/privacy"
}
