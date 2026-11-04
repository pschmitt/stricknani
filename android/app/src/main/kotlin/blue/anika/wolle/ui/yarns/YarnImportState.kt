package blue.anika.wolle.ui.yarns

import blue.anika.wolle.data.repository.ImportedYarnPreview
import blue.anika.wolle.data.repository.YarnImporter
import blue.anika.wolle.ui.projects.ProjectImportFailure
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** User-visible state for the yarn link import: fetch, then explicitly save. */
sealed interface YarnImportState {
    data object Idle : YarnImportState

    data class Importing(val url: String) : YarnImportState

    data class AwaitingConfirmation(val preview: ImportedYarnPreview) : YarnImportState

    data object Saving : YarnImportState

    data class Completed(val yarnId: Int, val preview: ImportedYarnPreview) : YarnImportState

    data class Failed(val url: String, val reason: ProjectImportFailure) : YarnImportState
}

/**
 * Small coroutine-backed state machine used by [ProjectsListViewModel]. Keeping it independent of
 * Hilt and Room makes cancellation, confirmation, and failure behavior directly unit-testable.
 */
class YarnImportController(
    private val scope: CoroutineScope,
    private val importer: YarnImporter,
    private val save: suspend (ImportedYarnPreview) -> Int,
) {
    private val _state = MutableStateFlow<YarnImportState>(YarnImportState.Idle)
    val state: StateFlow<YarnImportState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun start(url: String) {
        val normalizedUrl = url.trim()
        activeJob?.cancel()

        if (!isHttpUrl(normalizedUrl)) {
            _state.value = YarnImportState.Failed(normalizedUrl, ProjectImportFailure.InvalidUrl)
            return
        }

        _state.value = YarnImportState.Importing(normalizedUrl)
        activeJob = scope.launch {
            try {
                _state.value =
                    YarnImportState.AwaitingConfirmation(importer.importFromUrl(normalizedUrl))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _state.value = YarnImportState.Failed(normalizedUrl, classifyFailure(failure))
            }
        }
    }

    fun retry() {
        val failed = _state.value as? YarnImportState.Failed ?: return
        start(failed.url)
    }

    /** Saves the preview; [selectedImages] replaces the pictures to attach when not null. */
    fun confirm(selectedImages: List<String>? = null) {
        val confirmation = _state.value as? YarnImportState.AwaitingConfirmation ?: return
        val preview =
            selectedImages?.let { confirmation.preview.withImages(it) } ?: confirmation.preview
        activeJob?.cancel()
        _state.value = YarnImportState.Saving
        activeJob = scope.launch {
            try {
                val yarnId = save(preview)
                _state.value = YarnImportState.Completed(yarnId, preview)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _state.value =
                    YarnImportState.Failed(
                        confirmation.preview.sourceUrl,
                        classifyFailure(failure),
                    )
            }
        }
    }

    /** Dismisses an import, including an in-flight request, without saving a partial result. */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = YarnImportState.Idle
    }

    fun dismiss() {
        if (_state.value !is YarnImportState.Importing && _state.value !is YarnImportState.Saving) {
            cancel()
        }
    }

    private companion object {
        fun isHttpUrl(value: String): Boolean =
            runCatching {
                    URI(value).let { uri ->
                        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
                    }
                }
                .getOrDefault(false)

        fun classifyFailure(failure: Throwable): ProjectImportFailure =
            when {
                failure is IOException -> ProjectImportFailure.Offline
                failure is HttpException -> classifyHttp(failure)
                else -> ProjectImportFailure.Unknown
            }

        /**
         * v2 import errors (`src/api_v2/imports.rs`): 429 `rate_limited` -> try again later;
         * 422 `validation_error` (bad URL) and `unsafe_url` -> the "check the link" message;
         * `import_unavailable`, `feature_disabled`, timeouts, fetch failures and unparsable
         * documents -> "couldn't read that pattern".
         */
        fun classifyHttp(failure: HttpException): ProjectImportFailure {
            if (failure.code() == 429) return ProjectImportFailure.RateLimited
            val code =
                runCatching { failure.response()?.errorBody()?.string() }
                    .getOrNull()
                    ?.let { CODE_PATTERN.find(it)?.groupValues?.get(1) }
            return when {
                failure.code() == 422 && (code == "validation_error" || code == "unsafe_url") ->
                    ProjectImportFailure.InvalidUrl
                code == "import_blocked" -> ProjectImportFailure.Blocked
                code == "import_not_found" -> ProjectImportFailure.NotFound
                code == "feature_disabled" -> ProjectImportFailure.Disabled
                else -> ProjectImportFailure.Server
            }
        }

        val CODE_PATTERN = Regex("\"code\"\\s*:\\s*\"([a-z_]+)\"")
    }
}
