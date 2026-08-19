package blue.anika.wolle.ui.projects

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.ProjectWriteRequest
import blue.anika.wolle.data.api.dto.StepWriteRequest
import blue.anika.wolle.data.db.entity.CategoryEntity
import blue.anika.wolle.data.db.entity.YarnEntity
import blue.anika.wolle.data.repository.CategoryRepository
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.uploads.PendingUpload
import blue.anika.wolle.data.uploads.PendingUploadStore
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.common.MutationFeedback
import blue.anika.wolle.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val titleImages: List<PendingUpload> = emptyList(),
    val attachments: List<PendingUpload> = emptyList(),
)

data class ProjectEditorStep(
    val id: Int? = null,
    val title: String = "",
    val description: String = "",
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
    private val syncScheduler: SyncScheduler,
    private val mutationFeedback: MutationFeedback,
    private val pendingUploadStore: PendingUploadStore,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.ProjectEditor>()
    val isEditing: Boolean = route.projectId != null

    private val _form = MutableStateFlow(ProjectEditorFormState())
    val form: StateFlow<ProjectEditorFormState> = _form.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

    val yarns: StateFlow<List<YarnEntity>> =
        yarnRepository
            .observeAll()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                emptyList(),
            )

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
                                    )
                                },
                        )
                }
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

    fun removeStep(index: Int) {
        updateForm { it.copy(steps = it.steps.filterIndexed { position, _ -> position != index }) }
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

    fun removeTitleImage(index: Int) {
        val upload = _form.value.titleImages.getOrNull(index) ?: return
        updateForm {
            it.copy(titleImages = it.titleImages.filterIndexed { position, _ -> position != index })
        }
        viewModelScope.launch { pendingUploadStore.delete(upload) }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            runCatching { pendingUploadStore.copy(uri) }
                .onSuccess { upload -> updateForm { it.copy(attachments = it.attachments + upload) } }
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

    fun removeStepImage(stepIndex: Int, imageIndex: Int) {
        val upload = _form.value.steps.getOrNull(stepIndex)?.images?.getOrNull(imageIndex) ?: return
        updateStep(stepIndex) {
            it.copy(images = it.images.filterIndexed { position, _ -> position != imageIndex })
        }
        viewModelScope.launch { pendingUploadStore.delete(upload) }
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
                        tags = current.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
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
