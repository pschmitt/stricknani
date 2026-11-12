package blue.anika.wolle.ui.yarns

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ApiGeneration
import blue.anika.wolle.data.api.ImportAvailability
import blue.anika.wolle.data.api.dto.YarnPhotoDto
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.api.importAvailability
import blue.anika.wolle.data.api.v2.ImportJobDto
import blue.anika.wolle.data.imports.ImportDrafts
import blue.anika.wolle.data.imports.ImportTarget
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.ImportedYarnPreview
import blue.anika.wolle.data.repository.TrashKind
import blue.anika.wolle.data.repository.TrashRef
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.repository.access
import blue.anika.wolle.data.repository.movedItem
import blue.anika.wolle.data.repository.photosSortedByPosition
import blue.anika.wolle.data.repository.reorderedIdsOrNull
import blue.anika.wolle.data.repository.toYarnPreview
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.uploads.PendingUpload
import blue.anika.wolle.data.uploads.PendingUploadStore
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.OwnedKind
import blue.anika.wolle.ui.common.OwnerBannerData
import blue.anika.wolle.ui.common.PhotoRemoval
import blue.anika.wolle.ui.common.importImagePath
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.ownerBannerOf
import blue.anika.wolle.ui.common.urlHostOrNull
import blue.anika.wolle.ui.imports.EditorImportState
import blue.anika.wolle.ui.imports.ImportNotice
import blue.anika.wolle.ui.imports.LinkImportSession
import blue.anika.wolle.ui.navigation.Route
import blue.anika.wolle.ui.trash.restoredInOrder
import blue.anika.wolle.ui.trash.trashedByYarnSave
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class YarnEditorFormState(
    val name: String = "",
    val description: String = "",
    val brand: String = "",
    val colorway: String = "",
    val dyeLot: String = "",
    val fiberContent: String = "",
    val weightCategory: String = "",
    val recommendedNeedles: String = "",
    val weightGrams: String = "",
    val lengthMeters: String = "",
    val notes: String = "",
    val link: String = "",
    val isAiEnhanced: Boolean = false,
    val existingPhotos: List<YarnPhotoDto> = emptyList(),
    val photos: List<PendingUpload> = emptyList(),
    val removedPhotoIds: List<Int> = emptyList(),
    /** Pictures found on the imported page; [selectedImportImages] of them are attached on save. */
    val importImageUrls: List<String> = emptyList(),
    val selectedImportImages: Set<String> = emptySet(),
)

/**
 * The form with what an import found; a field the import has nothing for keeps what the user typed.
 * [notes] are the imported notes (gauge, care, price) the yarn has no field for.
 */
internal fun YarnEditorFormState.withImport(
    preview: ImportedYarnPreview,
    notes: String?,
): YarnEditorFormState {
    val imported = preview.request
    fun pick(value: String?, current: String) = value?.takeIf { it.isNotBlank() } ?: current
    return copy(
        name = pick(imported.name, name),
        description = pick(imported.description, description),
        brand = pick(imported.brand, brand),
        colorway = pick(imported.colorway, colorway),
        fiberContent = pick(imported.fiberContent, fiberContent),
        weightCategory = pick(imported.weightCategory, weightCategory),
        recommendedNeedles = pick(imported.recommendedNeedles, recommendedNeedles),
        weightGrams = imported.weightGrams?.toString() ?: weightGrams,
        lengthMeters = imported.lengthMeters?.toString() ?: lengthMeters,
        notes = listOf(this.notes, notes.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"),
        link = pick(imported.link, link),
        isAiEnhanced = imported.isAiEnhanced,
        importImageUrls = preview.imageUrls,
        selectedImportImages = imported.imageUrls.toSet(),
    )
}

@HiltViewModel
class YarnEditorViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val syncScheduler: SyncScheduler,
    private val mutationFeedback: MutationFeedback,
    private val pendingUploadStore: PendingUploadStore,
    private val capabilities: ApiCapabilities,
    private val settingsRepository: SettingsRepository,
    importDrafts: ImportDrafts,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.YarnEditor>()
    val isEditing: Boolean = route.yarnId != null

    /** The server's import of a link, for a new yarn; its result fills the form. */
    private val importSession =
        LinkImportSession(viewModelScope, importDrafts, ImportTarget.Yarn) {
            savedStateHandle[DRAFT_ID_KEY] = it
        }
    val importState: StateFlow<EditorImportState> = importSession.state

    private val _importNotice = MutableStateFlow<ImportNotice?>(null)
    /** Where the filled-in fields came from, once an import has been applied. */
    val importNotice: StateFlow<ImportNotice?> = _importNotice.asStateFlow()

    /** A link shared to the app: the import starts when the editor opens, the card shows it. */
    val sharedLink: String? = route.importUrl

    /** Link imports are on and the app talks to a server (not local-only mode). */
    fun offersLinkImport(): Boolean =
        !isEditing &&
            !settingsRepository.isLocalMode &&
            capabilities.current.importAvailability().url

    fun importAvailability(): ImportAvailability = capabilities.current.importAvailability()

    fun startImport(url: String) = importSession.start(url)

    /**
     * Fills the form from a finished import [draft]; [notesFor] writes the notes (gauge, care,
     * price) in the app's language.
     */
    fun applyImport(draft: ImportJobDto, notesFor: (ImportedYarnPreview) -> String?) {
        val dto = draft.preview
        if (dto == null) {
            importSession.applied()
            return
        }
        val source = draft.sourceUrl ?: dto.draft.sourceUrl.orEmpty()
        val preview = dto.toYarnPreview(source)
        _form.value = _form.value.withImport(preview, notesFor(preview))
        _importNotice.value =
            ImportNotice(
                host = urlHostOrNull(source)?.removePrefix("www.") ?: source,
                warnings = preview.warnings,
                aiFilled = preview.aiFields.isNotEmpty(),
                aiFallback = preview.aiFallback,
            )
        importSession.applied()
    }

    fun toggleImportImage(url: String) {
        updateForm {
            it.copy(
                selectedImportImages =
                    if (url in it.selectedImportImages) it.selectedImportImages - url
                    else it.selectedImportImages + url
            )
        }
    }

    /** The server's thumbnail proxy for a picture of the imported page. */
    fun importThumbnailUrl(url: String): String? = mediaUrlResolver.resolve(importImagePath(url))

    init {
        if (!isEditing) {
            val draftId = savedStateHandle.get<String>(DRAFT_ID_KEY) ?: route.draftId
            val link = route.importUrl
            when {
                draftId != null -> importSession.resume(draftId)
                link != null -> importSession.start(link)
            }
        }
    }

    /** Section to open the editor at, from a long-press on the yarn detail page. */
    val initialFocus: String? = route.focus

    private val _isLoaded = MutableStateFlow(!isEditing)
    /** True once an edited yarn's fields are in the form (immediately for a new one). */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _form = MutableStateFlow(YarnEditorFormState())
    val form: StateFlow<YarnEditorFormState> = _form.asStateFlow()

    /** The form as loaded (or empty for a new record); [isDirty] compares against it. */
    private val _initialForm = MutableStateFlow(YarnEditorFormState())

    /** The form as loaded: the server photos removed in [form] are still listed here. */
    val initialForm: StateFlow<YarnEditorFormState> = _initialForm.asStateFlow()

    /** True once anything differs from what was loaded - leaving then asks before discarding. */
    val isDirty: StateFlow<Boolean> =
        combine(_form, _initialForm) { current, initial -> current != initial }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Reordering photos needs the v2 API; the controls stay hidden on a server without it. */
    val reorderAvailable: StateFlow<Boolean> =
        capabilities.state
            .map { it.generation == ApiGeneration.V2 }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                capabilities.current.generation == ApiGeneration.V2,
            )

    /** False when the user may only read this yarn (a viewer, a derived yarn). */
    val canEdit: StateFlow<Boolean> =
        (route.yarnId?.let { yarnRepository.observeById(it) } ?: flowOf(null))
            .map { it?.access()?.canEdit ?: true }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), true)

    /** Whether the editor may delete the yarn: owners and administrators only. */
    val canDelete: StateFlow<Boolean> =
        (route.yarnId?.let { yarnRepository.observeById(it) } ?: flowOf(null))
            .map { it?.access()?.canManage ?: true }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), true)

    /** Banner for an administrator editing someone else's yarn; null for the user's own. */
    val ownerBanner: StateFlow<OwnerBannerData?> =
        combine(
                route.yarnId?.let { yarnRepository.observeById(it) } ?: flowOf(null),
                capabilities.state.map { it.user?.id }.distinctUntilChanged(),
            ) { yarn, me ->
                ownerBannerOf(yarn?.ownerBadge(me), OwnedKind.Yarn)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        val id = route.yarnId
        if (id != null) {
            viewModelScope.launch {
                val entity = yarnRepository.observeById(id).first()
                if (entity != null) {
                    val detail = yarnRepository.decodeDetail(entity)
                    _form.value =
                        YarnEditorFormState(
                            name = detail.name,
                            description = detail.description ?: "",
                            brand = detail.brand ?: "",
                            colorway = detail.colorway ?: "",
                            dyeLot = detail.dyeLot ?: "",
                            fiberContent = detail.fiberContent ?: "",
                            weightCategory = detail.weightCategory ?: "",
                            recommendedNeedles = detail.recommendedNeedles ?: "",
                            weightGrams = detail.weightGrams?.toString() ?: "",
                            lengthMeters = detail.lengthMeters?.toString() ?: "",
                            notes = detail.notes ?: "",
                            link = detail.link ?: "",
                            isAiEnhanced = detail.isAiEnhanced,
                            existingPhotos = detail.photos.photosSortedByPosition(),
                        )
                }
                _initialForm.value = _form.value
                _isLoaded.value = true
            }
        }
    }

    /** The yarn being edited; null while creating one. */
    val yarnId: Int?
        get() = route.yarnId

    /**
     * A saved photo was cropped or rotated on the server: show its new picture in the form (and in
     * the baseline, so the edit does not count as an unsaved change).
     */
    fun photoEdited(photoId: Int, url: String, thumbnailUrl: String, altText: String? = null) {
        fun YarnPhotoDto.swap() =
            if (id == photoId) {
                copy(url = url, thumbnailUrl = thumbnailUrl, altText = altText ?: this.altText)
            } else this
        fun YarnEditorFormState.swap() = copy(existingPhotos = existingPhotos.map { it.swap() })
        _form.value = _form.value.swap()
        _initialForm.value = _initialForm.value.swap()
    }

    fun updateForm(transform: (YarnEditorFormState) -> YarnEditorFormState) {
        _form.value = transform(_form.value)
    }

    fun imageUrl(path: String): String? = mediaUrlResolver.resolve(path)

    fun addPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                runCatching { pendingUploadStore.copy(uri) }
                    .onSuccess { upload -> updateForm { it.copy(photos = it.photos + upload) } }
                    .onFailure { mutationFeedback.show(R.string.editor_image_select_failed) }
            }
        }
    }

    fun removePhoto(index: Int): PhotoRemoval? {
        val upload = _form.value.photos.getOrNull(index) ?: return null
        updateForm {
            it.copy(photos = it.photos.filterIndexed { position, _ -> position != index })
        }
        return PhotoRemoval(
            undo = { updateForm { it.copy(photos = it.photos.inserted(index, upload)) } },
            finalize = { viewModelScope.launch { pendingUploadStore.delete(upload) } },
        )
    }

    /** Moves a server photo to another place; the first one is the yarn's main photo. */
    fun moveExistingPhoto(from: Int, to: Int) {
        updateForm { it.copy(existingPhotos = it.existingPhotos.movedItem(from, to)) }
    }

    fun removeExistingPhoto(photoId: Int): PhotoRemoval? {
        val index = _form.value.existingPhotos.indexOfFirst { it.id == photoId }
        if (index < 0) return null
        val photo = _form.value.existingPhotos[index]
        updateForm {
            it.copy(
                existingPhotos = it.existingPhotos.filterNot { existing -> existing.id == photoId },
                removedPhotoIds = it.removedPhotoIds + photoId,
            )
        }
        return PhotoRemoval(
            undo = {
                updateForm {
                    it.copy(
                        existingPhotos = it.existingPhotos.inserted(index, photo),
                        removedPhotoIds = it.removedPhotoIds - photoId,
                    )
                }
            },
            toTrash = true,
        )
    }

    /** Puts a removed server photo back at its original place (the persistent Undo). */
    fun restorePhoto(photoId: Int) {
        val initial = _initialForm.value.existingPhotos
        updateForm {
            it.copy(
                existingPhotos =
                    restoredInOrder(it.existingPhotos, initial, photoId) { photo -> photo.id },
                removedPhotoIds = it.removedPhotoIds - photoId,
            )
        }
    }

    fun save() {
        val current = _form.value
        if (current.name.isBlank()) {
            mutationFeedback.show(R.string.error_name_required)
            return
        }
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val request =
                    YarnWriteRequest(
                        name = current.name.trim(),
                        description = current.description.trim().ifBlank { null },
                        brand = current.brand.trim().ifBlank { null },
                        colorway = current.colorway.trim().ifBlank { null },
                        dyeLot = current.dyeLot.trim().ifBlank { null },
                        fiberContent = current.fiberContent.trim().ifBlank { null },
                        weightCategory = current.weightCategory.trim().ifBlank { null },
                        recommendedNeedles = current.recommendedNeedles.trim().ifBlank { null },
                        weightGrams = current.weightGrams.trim().toIntOrNull(),
                        lengthMeters = current.lengthMeters.trim().toIntOrNull(),
                        notes = current.notes.trim().ifBlank { null },
                        link = current.link.trim().ifBlank { null },
                        isAiEnhanced = current.isAiEnhanced,
                        imageUrls =
                            current.importImageUrls.filter { it in current.selectedImportImages },
                    )
                val id = route.yarnId
                val yarnId =
                    if (id != null) {
                        yarnRepository.updateYarn(id, request)
                        id
                    } else {
                        yarnRepository.createYarn(request)
                    }
                current.removedPhotoIds.distinct().forEach { photoId ->
                    yarnRepository.queuePhotoDelete(yarnId, photoId)
                }
                // The order goes before the uploads: a new photo has no server id yet, so an order
                // queued after it would be rejected as incomplete.
                if (id != null) {
                    reorderedIdsOrNull(
                            _initialForm.value.existingPhotos.map { it.id },
                            current.existingPhotos.map { it.id },
                            current.removedPhotoIds.toSet(),
                        )
                        ?.let { yarnRepository.queuePhotoOrder(yarnId, it) }
                }
                current.photos.forEach { upload -> yarnRepository.queuePhotoUpload(yarnId, upload) }
                // The import is used up: its draft no longer waits for review.
                if (id == null) importSession.finish()
                syncScheduler.replayThenSyncNow()
                val trashed =
                    if (id == null) emptyList() else trashedByYarnSave(current.removedPhotoIds)
                if (trashed.isEmpty()) {
                    mutationFeedback.showSaved(
                        if (id == null) R.string.mutation_yarn_created
                        else R.string.mutation_yarn_updated
                    )
                } else {
                    mutationFeedback.showTrashedBySave(
                        R.string.mutation_saved_removed_to_trash,
                        trashed,
                    )
                }
                _saved.value = true
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_save_failed)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun delete() {
        val id = route.yarnId ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                yarnRepository.deleteYarn(id)
                syncScheduler.replayThenSyncNow()
                mutationFeedback.showTrashed(
                    R.string.mutation_moved_to_trash,
                    TrashRef(TrashKind.Yarn, id),
                )
                _deleted.value = true
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_delete_failed)
            } finally {
                _isSaving.value = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val DRAFT_ID_KEY = "import_draft_id"
    }
}

private fun <T> List<T>.inserted(index: Int, item: T): List<T> =
    toMutableList().apply { add(index.coerceIn(0, size), item) }
