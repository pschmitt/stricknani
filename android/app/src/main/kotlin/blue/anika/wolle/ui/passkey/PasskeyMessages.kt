package blue.anika.wolle.ui.passkey

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import blue.anika.wolle.R
import blue.anika.wolle.data.passkey.PasskeyFailure
import blue.anika.wolle.data.passkey.PasskeyHost
import blue.anika.wolle.ui.common.MutationFeedback

/**
 * The string for a failed passkey step; same wording as the web app. All are `error_*` strings, so
 * the snackbar shows them with the error badge. A dismissed system sheet
 * ([PasskeyFailure.Cancelled]) has a message too, for places that want to say it, but callers
 * usually stay quiet about it.
 */
@StringRes
fun PasskeyFailure.messageResId(): Int =
    when (this) {
        PasskeyFailure.Cancelled -> R.string.error_passkey_cancelled
        PasskeyFailure.NoCredential -> R.string.error_passkey_no_credential
        PasskeyFailure.AlreadyRegistered -> R.string.error_passkey_already_registered
        PasskeyFailure.Unsupported -> R.string.error_passkey_unsupported
        PasskeyFailure.NotRecognized -> R.string.error_passkey_not_recognized
        PasskeyFailure.Expired -> R.string.error_passkey_expired
        PasskeyFailure.ReauthRequired -> R.string.error_passkey_reauth_required
        PasskeyFailure.LimitReached -> R.string.error_passkey_limit
        PasskeyFailure.NoPasskeys -> R.string.error_passkey_none
        PasskeyFailure.Unavailable -> R.string.error_passkey_unavailable
        PasskeyFailure.Rejected -> R.string.error_passkey_rejected
        is PasskeyFailure.RateLimited -> R.string.error_passkey_rate_limited
        PasskeyFailure.SessionExpired -> R.string.error_passkey_session_expired
        PasskeyFailure.Offline -> R.string.error_passkey_offline
        is PasskeyFailure.Other -> R.string.error_passkey_generic
    }

/** Shows [failure] in the app-wide snackbar, except a dismissed system sheet (not an error). */
fun MutationFeedback.showPasskeyFailure(failure: PasskeyFailure) {
    if (failure == PasskeyFailure.Cancelled) return
    show(failure.messageResId())
}

/** A [PasskeyHost] over the current Activity window, for the system passkey sheet. */
@Composable
fun rememberPasskeyHost(): PasskeyHost {
    val context = LocalContext.current
    return remember(context) { PasskeyHost { context } }
}
