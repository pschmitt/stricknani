package blue.anika.wolle.ui.oidc

import android.content.Context
import androidx.annotation.StringRes
import blue.anika.wolle.R
import blue.anika.wolle.data.oidc.OidcFailure
import blue.anika.wolle.ui.common.MutationFeedback

/** The localized text for an [OidcFailure]: a string resource and its `%1$s` provider name. */
@StringRes
fun OidcFailure.messageResId(): Int =
    when (this) {
        OidcFailure.Expired -> R.string.error_oidc_expired
        OidcFailure.Unlinked -> R.string.error_oidc_unlinked
        OidcFailure.Unavailable -> R.string.error_oidc_unavailable
        is OidcFailure.RateLimited ->
            if (retryAfterSeconds != null) R.string.error_oidc_rate_limited_seconds
            else R.string.error_oidc_rate_limited
        OidcFailure.AlreadyLinked -> R.string.error_oidc_already_linked
        OidcFailure.LastLoginMethod -> R.string.error_oidc_last_login_method
        OidcFailure.ReauthRequired -> R.string.error_oidc_reauth_required
        OidcFailure.InvalidPassword -> R.string.error_oidc_invalid_password
        OidcFailure.SessionExpired -> R.string.error_oidc_session_expired
        OidcFailure.NotFound -> R.string.error_oidc_not_found
        OidcFailure.Validation -> R.string.error_oidc_generic
        OidcFailure.ProviderError -> R.string.error_oidc_provider
        OidcFailure.AccountMismatch -> R.string.error_oidc_account_mismatch
        OidcFailure.Offline -> R.string.error_oidc_offline
        is OidcFailure.Other -> R.string.error_oidc_generic
    }

/** Renders [this] for display. [providerName] fills the `%1$s` of messages that name it. */
fun OidcFailure.message(context: Context, providerName: String): String =
    when (this) {
        is OidcFailure.RateLimited ->
            if (retryAfterSeconds != null)
                context.getString(messageResId(), providerName, retryAfterSeconds)
            else context.getString(messageResId(), providerName)
        else -> context.getString(messageResId(), providerName)
    }

/** The provider name to show when the server's list is not at hand. */
const val DEFAULT_PROVIDER_NAME = "Authelia"

/** Shows [failure] in the app-wide snackbar (an `error_*` string, so it gets the error badge). */
fun MutationFeedback.showOidcFailure(failure: OidcFailure, providerName: String) {
    val seconds = (failure as? OidcFailure.RateLimited)?.retryAfterSeconds
    if (seconds != null) show(failure.messageResId(), providerName, seconds)
    else show(failure.messageResId(), providerName)
}
