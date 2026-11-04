package blue.anika.wolle.ui.projects

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.ApiGeneration
import blue.anika.wolle.data.api.dto.ImageDto
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.ProjectEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.media.MediaUrlResolver
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.repository.movedItem
import blue.anika.wolle.data.repository.reorderedIdsOrNull
import blue.anika.wolle.data.repository.stepImages
import blue.anika.wolle.data.repository.titleImages
import blue.anika.wolle.data.uploads.PendingUpload
import blue.anika.wolle.data.uploads.PendingUploadStore
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.common.OwnedKind
import blue.anika.wolle.ui.common.OwnerBannerData
import blue.anika.wolle.ui.common.PhotoRemoval
import blue.anika.wolle.ui.common.ownerBadge
import blue.anika.wolle.ui.common.ownerBannerOf
import blue.anika.wolle.ui.common.yarnsOwnedBy
import blue.anika.wolle.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
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

data class ProjectEditorFormState(
    val name: String = "",
    val category: String = "",
    val needles: String = "",
    val stitchSample: String = "",
    val description: String = "",
    val notes: String = "",
    val otherMaterials: String = "",
    val tags: String = "",
    val link: String = "",
    val isAiEnhanced: Boolean = false,
    val selectedYarnIds: Set<Int> = emptySet(),
    val steps: List<ProjectEditorStep> = emptyList(),
    val existingTitleImages: List<ImageDto> = emptyList(),
    val titleImages: List<PendingUpload> = emptyList(),
    val removedImageIds: List<Int> = emptyList(),
    val attachments: List<PendingUpload> = emptyList(),
)

data class ProjectEditorStep(
    val id: Int? = null,
    val title: String = "",
    val description: String = "",
    val existingImages: List<ImageDto> = emptyList(),
    val images: List<PendingUpload> = emptyList(),
)

@HiltViewModel
class ProjectEditorViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    categoryRepository: CategoryRepository,
    yarnRepository: YarnRepository,
    private val mediaUrlResolver: MediaUrlResolver,
    private val syncScheduler: SyncScheduler,
    private val mutationFeedback: MutationFeedback,
    private val pendingUploadStore: PendingUploadStore,
    capabilities: ApiCapabilities,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.ProjectEditor>()
    val isEditing: Boolean = route.projectId != null

    /** Section or step to open the editor at, from a long-press on the detail page. */
    val initialFocus: String? = route.focus

    private val _isLoaded = MutableStateFlow(!isEditing)
    /** True once an edited project's fields are in the form (immediately for a new one). */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _form = MutableStateFlow(ProjectEditorFormState())
    val form: StateFlow<ProjectEditorFormState> = _form.asStateFlow()

    /** The form as loaded (or empty for a new record); [isDirty] compares against it. */
    private val _initialForm = MutableStateFlow(ProjectEditorFormState())

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

    /** Reordering images needs the v2 API; the controls stay hidden on a server without it. */
    val reorderAvailable: StateFlow<Boolean> =
        capabilities.state
            .map { it.generation == ApiGeneration.V2 }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                capabilities.current.generation == ApiGeneration.V2,
            )

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    private val editedProject: Flow<ProjectEntity?> =
        route.projectId?.let { projectRepository.observeById(it) } ?: flowOf(null)

    private val currentUserId: Flow<Long?> =
        capabilities.state.map { it.user?.id }.distinctUntilChanged()

    /**
     * The yarns that can be linked: the project owner's. On someone else's project (an
     * administrator editing it) that is the owner's stash, not the editor's, because the server
     * only links yarns of the project's owner.
     */
    val yarns: StateFlow<List<YarnEntity>> =
        combine(
                yarnRepository.observeAll(),
                editedProject.map { it?.ownerId }.distinctUntilChanged(),
                currentUserId,
            ) { all, ownerId, me ->
                yarnsOwnedBy(all, ownerId, me)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    /** Banner for an administrator editing someone else's project; null for the user's own. */
    val ownerBanner: StateFlow<OwnerBannerData?> =
        combine(editedProject, currentUserId) { project, me ->
                ownerBannerOf(project?.ownerBadge(me), OwnedKind.Project)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun previewUrl(yarn: YarnEntity): String? = mediaUrlResolver.resolve(yarn.previewUrl)

    init {
        val id = route.projectId
        if (id != null) {
            viewModelScope.launch {
                val entity = projectRepository.observeById(id).first()
                if (entity != null) {
                    val detail = projectRepository.decodeDetail(entity)
                    _form.value =
                        ProjectEditorFormState(
                            name = detail.name,
                            category = detail.category ?: "",
                            needles = detail.needles ?: "",
                            stitchSample = detail.stitchSample ?: "",
                            description = detail.description ?: "",
                            notes = detail.notes ?: "",
                            otherMaterials = detail.otherMaterials ?: "",
                            tags = detail.tags.joinToString(", "),
                            link = detail.link ?: "",
                            isAiEnhanced = detail.isAiEnhanced,
                            selectedYarnIds = detail.yarnIds.toSet(),
                            steps =
                                detail.steps.map { step ->
                                    ProjectEditorStep(
                                        id = step.id,
                                        title = step.title,
                                        description = step.description ?: "",
                                        existingImages = detail.stepImages(step.id),
                                    )
                                },
                            existingTitleImages = detail.titleImages(),
                        )
                }
                _initialForm.value = _form.value
                _isLoaded.value = true
            }
        }
    }

    fun updateForm(transform: (ProjectEditorFormState) -> ProjectEditorFormState) {
        _form.value = transform(_form.value)
    }

    fun toggleYarnSelected(yarnId: Int) {
        _form.value =
            _form.value.let { f ->
                f.copy(
                    selectedYarnIds =
                        if (yarnId in f.selectedYarnIds) f.selectedYarnIds - yarnId
                        else f.selectedYarnIds + yarnId
                )
            }
    }

    fun updateStep(index: Int, transform: (ProjectEditorStep) -> ProjectEditorStep) {
        updateForm { form ->
            form.copy(
                steps =
                    form.steps.mapIndexed { position, step ->
                        if (position == index) transform(step) else step
                    }
            )
        }
    }

    fun addStep() {
        updateForm { it.copy(steps = it.steps + ProjectEditorStep()) }
    }

    fun imageUrl(path: String): String? = mediaUrlResolver.resolve(path)

    fun removeStep(index: Int) {
        updateForm { it.copy(steps = it.steps.filterIndexed { position, _ -> position != index }) }
    }

    /** Puts a removed step back where it was (the Undo of [removeStep]). */
    fun insertStep(index: Int, step: ProjectEditorStep) {
        updateForm {
            val steps = it.steps.toMutableList()
            steps.add(index.coerceIn(0, steps.size), step)
            it.copy(steps = steps)
        }
    }

    fun moveStep(index: Int, direction: Int) {
        updateForm { it.copy(steps = moveProjectEditorStep(it.steps, index, direction)) }
    }

    fun addTitleImage(uri: Uri) {
        viewModelScope.launch {
            runCatching { pendingUploadStore.copy(uri) }
                .onSuccess { upload ->
                    updateForm { it.copy(titleImages = it.titleImages + upload) }
                }
                .onFailure { mutationFeedback.show(R.string.editor_image_select_failed) }
        }
    }

    fun removeTitleImage(index: Int): PhotoRemoval? {
        val upload = _form.value.titleImages.getOrNull(index) ?: return null
        updateForm {
            it.copy(titleImages = it.titleImages.filterIndexed { position, _ -> position != index })
        }
        return PhotoRemoval(
            undo = {
                updateForm {
                    it.copy(titleImages = it.titleImages.inserted(index, upload))
                }
            },
            finalize = { viewModelScope.launch { pendingUploadStore.delete(upload) } },
        )
    }

    fun removeExistingTitleImage(imageId: Int): PhotoRemoval? {
        val index = _form.value.existingTitleImages.indexOfFirst { it.id == imageId }
        if (index < 0) return null
        val image = _form.value.existingTitleImages[index]
        updateForm {
            it.copy(
                existingTitleImages =
                    it.existingTitleImages.filterNot { existing -> existing.id == imageId },
                removedImageIds = it.removedImageIds + imageId,
            )
        }
        return PhotoRemoval(
            undo = {
                updateForm {
                    it.copy(
                        existingTitleImages = it.existingTitleImages.inserted(index, image),
                        removedImageIds = it.removedImageIds - imageId,
                    )
                }
            }
        )
    }

    /** Moves a server title image to another place; the first one is the project cover. */
    fun moveTitleImage(from: Int, to: Int) {
        updateForm {
            it.copy(existingTitleImages = it.existingTitleImages.movedItem(from, to))
        }
    }

    fun moveStepImage(stepIndex: Int, from: Int, to: Int) {
        updateStep(stepIndex) { it.copy(existingImages = it.existingImages.movedItem(from, to)) }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            runCatching { pendingUploadStore.copy(uri) }
                .onSuccess { upload ->
                    updateForm { it.copy(attachments = it.attachments + upload) }
                }
                .onFailure { mutationFeedback.show(R.string.editor_file_select_failed) }
        }
    }

    fun removeAttachment(index: Int) {
        val upload = _form.value.attachments.getOrNull(index) ?: return
        updateForm {
            it.copy(attachments = it.attachments.filterIndexed { position, _ -> position != index })
        }
        viewModelScope.launch { pendingUploadStore.delete(upload) }
    }

    fun addStepImage(index: Int, uri: Uri) {
        viewModelScope.launch {
            runCatching { pendingUploadStore.copy(uri, stepIndex = index) }
                .onSuccess { upload -> updateStep(index) { it.copy(images = it.images + upload) } }
                .onFailure { mutationFeedback.show(R.string.editor_image_select_failed) }
        }
    }

    fun removeStepImage(stepIndex: Int, imageIndex: Int): PhotoRemoval? {
        val upload =
            _form.value.steps.getOrNull(stepIndex)?.images?.getOrNull(imageIndex) ?: return null
        updateStep(stepIndex) {
            it.copy(images = it.images.filterIndexed { position, _ -> position != imageIndex })
        }
        return PhotoRemoval(
            undo = {
                updateStep(stepIndex) { it.copy(images = it.images.inserted(imageIndex, upload)) }
            },
            finalize = { viewModelScope.launch { pendingUploadStore.delete(upload) } },
        )
    }

    fun removeExistingStepImage(stepIndex: Int, imageId: Int): PhotoRemoval? {
        val index =
            _form.value.steps.getOrNull(stepIndex)?.existingImages?.indexOfFirst {
                it.id == imageId
            } ?: -1
        if (index < 0) return null
        val image = _form.value.steps[stepIndex].existingImages[index]
        updateStep(stepIndex) { step ->
            step.copy(
                existingImages =
                    step.existingImages.filterNot { existing -> existing.id == imageId }
            )
        }
        updateForm { it.copy(removedImageIds = it.removedImageIds + imageId) }
        return PhotoRemoval(
            undo = {
                updateStep(stepIndex) {
                    it.copy(existingImages = it.existingImages.inserted(index, image))
                }
                updateForm { it.copy(removedImageIds = it.removedImageIds - imageId) }
            }
        )
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
                    ProjectWriteRequest(
                        name = current.name.trim(),
                        category = current.category.trim().ifBlank { null },
                        needles = current.needles.trim().ifBlank { null },
                        stitchSample = current.stitchSample.trim().ifBlank { null },
                        description = current.description.trim().ifBlank { null },
                        notes = current.notes.trim().ifBlank { null },
                        otherMaterials = current.otherMaterials.trim().ifBlank { null },
                        tags = parseTagInput(current.tags),
                        link = current.link.trim().ifBlank { null },
                        isAiEnhanced = current.isAiEnhanced,
                        yarnIds = current.selectedYarnIds.toList(),
                        steps =
                            current.steps.mapIndexed { index, step ->
                                StepWriteRequest(
                                    id = step.id,
                                    title = step.title.trim().ifBlank { "Step ${index + 1}" },
                                    description = step.description.trim().ifBlank { null },
                                    stepNumber = index + 1,
                                )
                            },
                    )
                val id = route.projectId
                val projectId =
                    if (id != null) {
                        projectRepository.updateProject(id, request)
                        id
                    } else {
                        projectRepository.createProject(request)
                    }
                current.removedImageIds.distinct().forEach { imageId ->
                    projectRepository.queueImageDelete(projectId, imageId)
                }
                // Orders go before the uploads: a new image has no server id yet, so an order
                // queued after it would be rejected as incomplete.
                if (id != null) queueImageOrders(id, current)
                current.titleImages.forEach { upload ->
                    projectRepository.queueTitleImageUpload(projectId, upload)
                }
                current.steps.forEachIndexed { index, step ->
                    step.images.forEach { upload ->
                        projectRepository.queueStepImageUpload(
                            projectId,
                            upload.copy(stepIndex = index),
                        )
                    }
                }
                current.attachments.forEach { upload ->
                    projectRepository.queueAttachmentUpload(projectId, upload)
                }
                syncScheduler.replayThenSyncNow()
                mutationFeedback.show(
                    if (id == null) R.string.mutation_project_created_queued
                    else R.string.mutation_project_updated_queued
                )
                _saved.value = true
            } catch (e: Exception) {
                mutationFeedback.show(R.string.error_save_failed)
            } finally {
                _isSaving.value = false
            }
        }
    }

    private suspend fun queueImageOrders(projectId: Int, current: ProjectEditorFormState) {
        val initial = _initialForm.value
        val removed = current.removedImageIds.toSet()
        reorderedIdsOrNull(
                initial.existingTitleImages.map { it.id },
                current.existingTitleImages.map { it.id },
                removed,
            )
            ?.let { projectRepository.queueTitleImageOrder(projectId, it) }
        current.steps.forEach { step ->
            val stepId = step.id ?: return@forEach
            val initialStep = initial.steps.firstOrNull { it.id == stepId } ?: return@forEach
            reorderedIdsOrNull(
                    initialStep.existingImages.map { it.id },
                    step.existingImages.map { it.id },
                    removed,
                )
                ?.let { projectRepository.queueStepImageOrder(projectId, stepId, it) }
        }
    }

    fun delete() {
        val id = route.projectId ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                projectRepository.deleteProject(id)
                syncScheduler.replayThenSyncNow()
                mutationFeedback.show(R.string.mutation_project_deleted_queued)
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
    }
}

/**
 * The editor's comma-separated tag field as a tag list. Splits on commas only: the v2 API stores
 * every list item as one verbatim label (so "winter gift" stays one tag), whereas v1 re-split each
 * item on spaces and `#` on the server. Blank items and case-insensitive duplicates are dropped, as
 * the server did.
 */
internal fun parseTagInput(input: String): List<String> =
    input.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

private fun <T> List<T>.inserted(index: Int, item: T): List<T> =
    toMutableList().apply { add(index.coerceIn(0, size), item) }
