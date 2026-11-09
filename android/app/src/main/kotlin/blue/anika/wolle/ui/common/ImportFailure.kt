package blue.anika.wolle.ui.common

import androidx.annotation.StringRes
import blue.anika.wolle.R
import java.io.IOException
import retrofit2.HttpException

/** Why a link import did not work, as the user is told. */
enum class ImportFailure {
    InvalidUrl,
    Offline,
    RateLimited,
    /** Too many imports running at once. */
    TooManyImports,
    /** Too many imports waiting for review. */
    TooManyDrafts,
    /** The site refused the server's request (bot protection, login wall). */
    Blocked,
    NotFound,
    /** Link import is off on this server. */
    Disabled,
    Server,
    /** A pasted text or picked file is over the 5 MiB limit. */
    TooLarge,
    /** The server could not read the pasted text or the picked file. */
    Unreadable,
    /** The draft was removed (dismissed elsewhere). */
    Gone,
    Unknown,
}

/**
 * The failure of a request to the import API: a network error, or the server's status and error
 * `code` (`src/api_v2/imports.rs`). A failed draft carries the same pair, see
 * [classifyImportError].
 */
fun classifyImportFailure(failure: Throwable): ImportFailure =
    when (failure) {
        is IOException -> ImportFailure.Offline
        is HttpException ->
            classifyImportError(
                failure.code(),
                runCatching { failure.response()?.errorBody()?.string() }
                    .getOrNull()
                    ?.let { CODE_PATTERN.find(it)?.groupValues?.get(1) },
            )
        else -> ImportFailure.Unknown
    }

/**
 * 429 `too_many_imports` / `rate_limited`, 409 `too_many_drafts`, 422 `validation_error` and
 * `unsafe_url` (a bad link), `feature_disabled`, and the fetch errors of a draft that ran.
 */
fun classifyImportError(status: Int?, code: String?): ImportFailure =
    when {
        code == "too_many_imports" -> ImportFailure.TooManyImports
        code == "too_many_drafts" -> ImportFailure.TooManyDrafts
        status == 429 || code == "rate_limited" -> ImportFailure.RateLimited
        code == "payload_too_large" || status == 413 -> ImportFailure.TooLarge
        code == "unsupported_document" || code == "invalid_document" -> ImportFailure.Unreadable
        code == "validation_error" || code == "unsafe_url" -> ImportFailure.InvalidUrl
        code == "import_blocked" -> ImportFailure.Blocked
        code == "import_not_found" -> ImportFailure.NotFound
        code == "feature_disabled" -> ImportFailure.Disabled
        else -> ImportFailure.Server
    }

private val CODE_PATTERN = Regex("\"code\"\\s*:\\s*\"([a-z_]+)\"")

@StringRes
fun importFailureRes(failure: ImportFailure, yarn: Boolean = false): Int =
    when (failure) {
        ImportFailure.InvalidUrl ->
            if (yarn) R.string.yarn_import_error_invalid_url
            else R.string.project_import_error_invalid_url
        ImportFailure.Offline -> R.string.project_import_error_offline
        ImportFailure.RateLimited -> R.string.project_import_error_rate_limited
        ImportFailure.TooManyImports -> R.string.import_error_too_many_imports
        ImportFailure.TooManyDrafts -> R.string.import_error_too_many_drafts
        ImportFailure.Blocked -> R.string.import_error_blocked
        ImportFailure.NotFound -> R.string.import_error_not_found
        ImportFailure.Disabled -> R.string.import_error_disabled
        ImportFailure.Server ->
            if (yarn) R.string.yarn_import_error_server else R.string.project_import_error_server
        ImportFailure.TooLarge -> R.string.import_error_too_large
        ImportFailure.Unreadable -> R.string.import_error_unreadable
        ImportFailure.Gone -> R.string.import_error_gone
        ImportFailure.Unknown ->
            if (yarn) R.string.yarn_import_error_unknown else R.string.project_import_error_unknown
    }
