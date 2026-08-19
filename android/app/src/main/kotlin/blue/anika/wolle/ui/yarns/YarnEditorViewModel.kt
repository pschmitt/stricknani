package blue.anika.wolle.ui.yarns

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import blue.anika.wolle.R
import blue.anika.wolle.data.api.dto.YarnWriteRequest
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.sync.SyncScheduler
import blue.anika.wolle.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
)

@HiltViewModel
class YarnEditorViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val yarnRepository: YarnRepository,
    private val syncScheduler: SyncScheduler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.YarnEditor>()
    val isEditing: Boolean = route.yarnId != null

    private val _form = MutableStateFlow(YarnEditorFormState())
    val form: StateFlow<YarnEditorFormState> = _form.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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
                        )
                }
            }
        }
    }

    fun updateForm(transform: (YarnEditorFormState) -> YarnEditorFormState) {
        _form.value = transform(_form.value)
    }

    fun save() {
        val current = _form.value
        if (current.name.isBlank()) {
            _errorMessage.value = context.getString(R.string.error_name_required)
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
                    )
                val id = route.yarnId
                if (id != null) yarnRepository.updateYarn(id, request)
                else yarnRepository.createYarn(request)
                syncScheduler.replayThenSyncNow()
                _saved.value = true
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_save_failed)
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
                _deleted.value = true
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_delete_failed)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
