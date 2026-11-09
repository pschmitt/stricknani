package blue.anika.wolle.ui.imports

import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.api.v2.MAX_IMPORT_SOURCE_BYTES
import blue.anika.wolle.data.imports.DRAFT_POLL_MILLIS
import blue.anika.wolle.data.imports.ImportDraftSource
import blue.anika.wolle.data.imports.ImportFile
import blue.anika.wolle.data.imports.ImportTarget
import blue.anika.wolle.ui.common.ImportFailure
import blue.anika.wolle.ui.common.classifyImportError
import blue.anika.wolle.ui.common.classifyImportFailure
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** What the import card of an editor shows. */
sealed interface EditorImportState {
    data object Idle : EditorImportState

    /** The server is reading [url]; the user may leave, the draft waits. */
    data class Running(val url: String) : EditorImportState

    data class Failed(val url: String, val failure: ImportFailure) : EditorImportState

    /**
     * The draft is ready; the editor fills its fields from it and calls
     * [LinkImportSession.applied].
     */
    data class Ready(val draft: ImportJobDto) : EditorImportState
}

/**
 * The import of an editor: starts the server's draft for a link (or picks up one the user already
 * started), polls it every two seconds and reports the result; the editor fills itself from it. The
 * draft stays on the server until [finish] (the editor saved) or the user dismisses it.
 */
class LinkImportSession(
    private val scope: CoroutineScope,
    private val drafts: ImportDraftSource,
    private val target: ImportTarget,
    /** Remembers the draft id, so a recreated editor does not start a second import. */
    private val onDraftId: (String?) -> Unit = {},
) {
    private val _state = MutableStateFlow<EditorImportState>(EditorImportState.Idle)
    val state: StateFlow<EditorImportState> = _state.asStateFlow()

    /** The draft this editor works from, once there is one. */
    var draftId: String? = null
        private set

    private var job: Job? = null

    /** Imports [link] as a new draft. */
    fun start(link: String) {
        val url = link.trim()
        if (!isHttpUrl(url)) {
            _state.value = EditorImportState.Failed(url, ImportFailure.InvalidUrl)
            return
        }
        job?.cancel()
        _state.value = EditorImportState.Running(url)
        job = scope.launch {
            val draft =
                try {
                    drafts.start(url, target)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    _state.value = EditorImportState.Failed(url, classifyImportFailure(failure))
                    return@launch
                }
            remember(draft.id)
            poll(draft.id, url)
        }
    }

    /** Imports pasted [text] (a project) as a new draft. */
    fun startText(text: String) {
        if (text.isBlank()) return
        if (text.toByteArray().size > MAX_IMPORT_SOURCE_BYTES) {
            _state.value = EditorImportState.Failed("", ImportFailure.TooLarge)
            return
        }
        begin { drafts.startText(text) }
    }

    /** Reads [file] at once; the result comes back without a draft on the server. */
    fun startFile(file: ImportFile) {
        if (file.bytes.size > MAX_IMPORT_SOURCE_BYTES) {
            _state.value = EditorImportState.Failed("", ImportFailure.TooLarge)
            return
        }
        job?.cancel()
        _state.value = EditorImportState.Running("")
        job = scope.launch {
            try {
                _state.value = EditorImportState.Ready(drafts.previewFile(file))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _state.value = EditorImportState.Failed("", classifyImportFailure(failure))
            }
        }
    }

    /** Fails a source that cannot be read (a picked file that could not be opened). */
    fun fail(failure: ImportFailure) {
        _state.value = EditorImportState.Failed("", failure)
    }

    private fun begin(create: suspend () -> ImportJobDto) {
        job?.cancel()
        _state.value = EditorImportState.Running("")
        job = scope.launch {
            val draft =
                try {
                    create()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    _state.value = EditorImportState.Failed("", classifyImportFailure(failure))
                    return@launch
                }
            remember(draft.id)
            poll(draft.id, "")
        }
    }

    /** Continues with draft [id]: a waiting card or the notification was tapped. */
    fun resume(id: String) {
        job?.cancel()
        remember(id)
        _state.value = EditorImportState.Running("")
        job = scope.launch { poll(id, "") }
    }

    /** The editor took the result over. */
    fun applied() {
        if (_state.value is EditorImportState.Ready) _state.value = EditorImportState.Idle
    }

    /** The editor saved what the draft made: the draft is used up. */
    suspend fun finish() {
        val id = draftId ?: return
        runCatching { drafts.delete(id) }
        remember(null)
    }

    private fun remember(id: String?) {
        draftId = id
        onDraftId(id)
    }

    private suspend fun poll(id: String, knownUrl: String) {
        while (true) {
            try {
                val draft = drafts.get(id)
                val url = knownUrl.ifBlank { draft.sourceUrl.orEmpty() }
                when {
                    draft.isReady -> {
                        drafts.seen(draft)
                        _state.value = EditorImportState.Ready(draft)
                        return
                    }
                    draft.isFailed -> {
                        drafts.seen(draft)
                        _state.value =
                            EditorImportState.Failed(
                                url,
                                classifyImportError(draft.errorStatus, draft.error?.code),
                            )
                        return
                    }
                    else -> _state.value = EditorImportState.Running(url)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                // No connection for a moment: the draft is on the server, ask again.
            } catch (failure: HttpException) {
                // Dismissed elsewhere (404), or the server is unwell: stop asking.
                remember(null)
                _state.value =
                    EditorImportState.Failed(
                        knownUrl,
                        if (failure.code() == 404) ImportFailure.Gone
                        else classifyImportFailure(failure),
                    )
                return
            }
            delay(DRAFT_POLL_MILLIS)
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        runCatching {
                URI(value).let { uri ->
                    (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
                }
            }
            .getOrDefault(false)
}
