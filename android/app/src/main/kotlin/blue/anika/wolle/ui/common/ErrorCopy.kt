package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import blue.anika.wolle.R
import blue.anika.wolle.data.repository.AdminFailure
import blue.anika.wolle.ui.common.mascot.MascotMood

/**
 * Which playful error panel to show. The copy mirrors the web app (`web/src/lib/error-copy.ts` and
 * the nginx 502/503/504 pages) so both clients say the same things.
 */
enum class ErrorKind(
    val mood: MascotMood,
    @StringRes val kicker: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val quips: List<Int>,
) {
    /** A generic 404. */
    NotFound(
        MascotMood.Confused,
        R.string.error_not_found_kicker,
        R.string.error_not_found_title,
        R.string.error_not_found_body,
        listOf(
            R.string.error_not_found_quip_1,
            R.string.error_not_found_quip_2,
            R.string.error_not_found_quip_3,
            R.string.error_not_found_quip_4,
        ),
    ),
    ProjectMissing(
        MascotMood.Dizzy,
        R.string.error_project_missing_kicker,
        R.string.error_project_missing_title,
        R.string.error_project_missing_body,
        listOf(R.string.error_project_missing_quip_1, R.string.error_project_missing_quip_2),
    ),
    YarnMissing(
        MascotMood.Confused,
        R.string.error_yarn_missing_kicker,
        R.string.error_yarn_missing_title,
        R.string.error_yarn_missing_body,
        listOf(R.string.error_yarn_missing_quip_1, R.string.error_yarn_missing_quip_2),
    ),
    /** 401 / 403. */
    Forbidden(
        MascotMood.Tired,
        R.string.error_forbidden_kicker,
        R.string.error_forbidden_title,
        R.string.error_forbidden_body,
        listOf(R.string.error_forbidden_quip_1),
    ),
    /** A plain 500, or anything unexpected. */
    Server(
        MascotMood.Dizzy,
        R.string.error_server_kicker,
        R.string.error_server_title,
        R.string.error_server_body,
        listOf(R.string.error_server_quip_1, R.string.error_server_quip_2),
    ),
    BadGateway(
        MascotMood.Dizzy,
        R.string.error_bad_gateway_kicker,
        R.string.error_bad_gateway_title,
        R.string.error_bad_gateway_body,
        listOf(R.string.error_bad_gateway_quip_1),
    ),
    /** 503, also used when the internet works but the server cannot be reached. */
    Unavailable(
        MascotMood.Tired,
        R.string.error_unavailable_kicker,
        R.string.error_unavailable_title,
        R.string.error_unavailable_body,
        listOf(R.string.error_unavailable_quip_1),
    ),
    Timeout(
        MascotMood.Sleepy,
        R.string.error_timeout_kicker,
        R.string.error_timeout_title,
        R.string.error_timeout_body,
        listOf(R.string.error_timeout_quip_1),
    ),
    /** The device has no working internet connection. */
    Offline(
        MascotMood.Sad,
        R.string.error_offline_kicker,
        R.string.error_offline_title,
        R.string.error_offline_body,
        listOf(R.string.error_offline_quip_1),
    );

    /** Whether trying the same request again can plausibly help. */
    val retryable: Boolean
        get() =
            when (this) {
                NotFound,
                ProjectMissing,
                YarnMissing,
                Forbidden -> false
                else -> true
            }
}

/**
 * The panel for an HTTP [status] (null or 0 for no response at all). [offline] says the device has
 * no connection; it wins over everything but a real status code.
 */
fun errorKindForStatus(status: Int?, offline: Boolean = false): ErrorKind =
    when (status) {
        404 -> ErrorKind.NotFound
        401,
        403 -> ErrorKind.Forbidden
        502 -> ErrorKind.BadGateway
        503 -> ErrorKind.Unavailable
        504 -> ErrorKind.Timeout
        else ->
            if (offline || status == null || status == 0) ErrorKind.Offline else ErrorKind.Server
    }

/** The panel for a failed refresh; null when the state is not a failure. */
fun RefreshState.errorKind(): ErrorKind? =
    when (this) {
        RefreshState.Offline -> ErrorKind.Offline
        RefreshState.ServerUnreachable -> ErrorKind.Unavailable
        RefreshState.Error -> ErrorKind.Server
        else -> null
    }

/** The panel for a failed admin call. */
fun AdminFailure.errorKind(): ErrorKind =
    when (this) {
        AdminFailure.Offline -> ErrorKind.Offline
        AdminFailure.NotAllowed -> ErrorKind.Forbidden
        AdminFailure.NotFound -> ErrorKind.NotFound
        else -> ErrorKind.Server
    }

/**
 * The same [seed] always gets the same entry, so a footnote does not change while a screen
 * recomposes. Same hash as the web's `pickQuip`.
 */
fun <T> pickQuip(seed: String, quips: List<T>): T? {
    if (quips.isEmpty()) return null
    var hash = 0L
    for (char in seed) hash = (hash * 31 + char.code) and 0xFFFFFFFFL
    return quips[(hash % quips.size).toInt()]
}
