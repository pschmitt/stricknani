package blue.anika.wolle.ui.projects

import blue.anika.wolle.data.repository.ImportedProjectPreview
import blue.anika.wolle.data.repository.ProjectImporter
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

/** User-visible state for the two-phase import flow: fetch, then explicitly save. */
sealed interface ProjectImportState {
    data object Idle : ProjectImportState

    data class Importing(val url: String) : ProjectImportState

    data class AwaitingConfirmation(val preview: ImportedProjectPreview) : ProjectImportState

    data object Saving : ProjectImportState

    data class Completed(val projectId: Int, val preview: ImportedProjectPreview) :
        ProjectImportState

    data class Failed(val url: String, val reason: ProjectImportFailure) : ProjectImportState
}

enum class ProjectImportFailure {
    InvalidUrl,
    Offline,
    Server,
    Unknown,
}

/**
 * Small coroutine-backed state machine used by [ProjectsListViewModel]. Keeping it independent of
 * Hilt and Room makes cancellation, confirmation, and failure behavior directly unit-testable.
 */
class ProjectImportController(
    private val scope: CoroutineScope,
    private val importer: ProjectImporter,
    private val save: suspend (ImportedProjectPreview) -> Int,
) {
    private val _state = MutableStateFlow<ProjectImportState>(ProjectImportState.Idle)
    val state: StateFlow<ProjectImportState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun start(url: String) {
        val normalizedUrl = url.trim()
        activeJob?.cancel()

        if (!isHttpUrl(normalizedUrl)) {
            _state.value = ProjectImportState.Failed(normalizedUrl, ProjectImportFailure.InvalidUrl)
            return
        }

        _state.value = ProjectImportState.Importing(normalizedUrl)
        activeJob = scope.launch {
            try {
                _state.value =
                    ProjectImportState.AwaitingConfirmation(importer.importFromUrl(normalizedUrl))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _state.value = ProjectImportState.Failed(normalizedUrl, classifyFailure(failure))
            }
        }
    }

    fun retry() {
        val failed = _state.value as? ProjectImportState.Failed ?: return
        start(failed.url)
    }

    fun confirm() {
        val confirmation = _state.value as? ProjectImportState.AwaitingConfirmation ?: return
        activeJob?.cancel()
        _state.value = ProjectImportState.Saving
        activeJob = scope.launch {
            try {
                val projectId = save(confirmation.preview)
                _state.value = ProjectImportState.Completed(projectId, confirmation.preview)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _state.value =
                    ProjectImportState.Failed(
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
        _state.value = ProjectImportState.Idle
    }

    fun dismiss() {
        if (
            _state.value !is ProjectImportState.Importing &&
                _state.value !is ProjectImportState.Saving
        ) {
            cancel()
        }
    }

    private companion object {
        fun isHttpUrl(value: String): Boolean = runCatching {
            URI(value).let { uri ->
                (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
            }
        }
            .getOrDefault(false)

        fun classifyFailure(failure: Throwable): ProjectImportFailure =
            when {
                failure is IOException -> ProjectImportFailure.Offline
                failure is HttpException -> ProjectImportFailure.Server
                else -> ProjectImportFailure.Unknown
            }
    }
}
