package com.ollama.android.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ollama.android.data.AvailableModel
import com.ollama.android.data.DownloadState
import com.ollama.android.data.ModelCatalog
import com.ollama.android.data.ModelRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ModelItem(
    val model: AvailableModel,
    val downloadState: DownloadState,
    val isDownloaded: Boolean
)

data class ModelsUiState(
    val models: List<ModelItem> = emptyList()
)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ModelRepository.getInstance(application)

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    init {
        // Observe download states for all models
        ModelCatalog.models.forEach { model ->
            viewModelScope.launch {
                repo.getDownloadState(model.id).collect { state ->
                    updateModelState(model.id, state)
                }
            }
        }
        refreshModels()
    }

    private fun updateModelState(modelId: String, downloadState: DownloadState) {
        _uiState.update { state ->
            val models = state.models.map { item ->
                if (item.model.id == modelId) {
                    item.copy(
                        downloadState = downloadState,
                        isDownloaded = downloadState is DownloadState.Completed
                    )
                } else item
            }
            state.copy(models = models)
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val items = ModelCatalog.models.map { model ->
                ModelItem(
                    model = model,
                    downloadState = if (repo.isModelDownloaded(model.id))
                        DownloadState.Completed else DownloadState.Idle,
                    isDownloaded = repo.isModelDownloaded(model.id)
                )
            }
            _uiState.value = ModelsUiState(models = items)
        }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            repo.downloadModel(modelId)
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            repo.deleteModel(modelId)
            refreshModels()
        }
    }
}
