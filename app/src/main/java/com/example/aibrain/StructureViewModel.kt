package com.example.aibrain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel для управления состоянием структуры и взаимодействия с API.
 */
class StructureViewModel(
    private var apiService: ApiService
) : ViewModel() {

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState

    fun setConnectionState(status: ConnectionStatus, detail: String = "") {
        _connectionState.value = ConnectionState(status = status, detail = detail)
    }

    private val _editMode = MutableStateFlow(EditMode.EDIT)
    val editMode: StateFlow<EditMode> = _editMode

    private val _structureState = MutableStateFlow<StructureState>(StructureState.Idle)
    val structureState: StateFlow<StructureState> = _structureState

    private var currentSessionId: String? = null
    private val snapshots = mutableListOf<StructureSnapshot>()
    private var currentSnapshotIndex = -1
    private val maxSnapshots = 20

    data class StructureSnapshot(
        val timestamp: Long,
        val elements: List<ScaffoldElement>,
        val description: String
    )

    fun setSessionId(sessionId: String) {
        currentSessionId = sessionId
    }

    fun updateApiService(newApiService: ApiService) {
        apiService = newApiService
    }

    /**
     * Сохранить текущее состояние структуры.
     */
    fun saveSnapshot(elements: List<ScaffoldElement>, description: String) {
        if (currentSnapshotIndex < snapshots.size - 1) {
            snapshots.subList(currentSnapshotIndex + 1, snapshots.size).clear()
        }

        val copiedElements = elements.map { it.copy() }
        val previousSnapshot = snapshots.lastOrNull()
        if (previousSnapshot != null && previousSnapshot.elements == copiedElements) {
            return
        }

        val snapshot = StructureSnapshot(
            timestamp = System.currentTimeMillis(),
            elements = copiedElements,
            description = description
        )

        snapshots.add(snapshot)

        if (snapshots.size > maxSnapshots) {
            snapshots.removeAt(0)
        }

        currentSnapshotIndex = snapshots.lastIndex
    }

    /**
     * Отменить последнее действие (Undo).
     */
    fun undo(onRestore: (StructureSnapshot) -> Unit): Boolean {
        if (currentSnapshotIndex > 0) {
            currentSnapshotIndex--
            val snapshot = snapshots[currentSnapshotIndex]
            onRestore(snapshot)
            return true
        }
        return false
    }

    /**
     * Повторить отмененное действие (Redo).
     */
    fun redo(onRestore: (StructureSnapshot) -> Unit): Boolean {
        if (currentSnapshotIndex < snapshots.size - 1) {
            currentSnapshotIndex++
            val snapshot = snapshots[currentSnapshotIndex]
            onRestore(snapshot)
            return true
        }
        return false
    }

    /**
     * Можно ли сделать Undo.
     */
    fun canUndo(): Boolean = currentSnapshotIndex > 0

    /**
     * Можно ли сделать Redo.
     */
    fun canRedo(): Boolean = currentSnapshotIndex < snapshots.size - 1

    /**
     * Получить описание последнего действия для Undo.
     */
    fun getUndoDescription(): String? {
        if (currentSnapshotIndex > 0) {
            return snapshots[currentSnapshotIndex].description
        }
        return null
    }

    fun toggleEditMode() {
        _editMode.value = when (_editMode.value) {
            EditMode.EDIT -> EditMode.SIMULATION
            EditMode.SIMULATION -> EditMode.EDIT
        }
    }

    /**
     * Предварительный просмотр: что произойдет если удалить элемент.
     */
    fun previewRemoveElement(elementId: String, onResult: (PreviewResponse) -> Unit) {
        val sessionId = currentSessionId ?: return

        viewModelScope.launch {
            try {
                val response = apiService.previewRemove(sessionId, elementId)
                if (response.isSuccessful && response.body() != null) {
                    onResult(response.body()!!)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Удалить элемент из структуры.
     */
    fun removeElement(
        elementId: String,
        onSuccess: (UpdateResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val sessionId = currentSessionId ?: run {
            onError("No active session")
            return
        }

        _structureState.value = StructureState.Updating

        viewModelScope.launch {
            try {
                val action = UpdateAction(
                    action = "REMOVE",
                    element_id = elementId
                )

                val response = apiService.updateStructure(sessionId, action)

                if (response.isSuccessful && response.body() != null) {
                    val updateResponse = response.body()!!
                    _structureState.value = StructureState.Updated(updateResponse)
                    onSuccess(updateResponse)
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    _structureState.value = StructureState.Error(errorMsg)
                    onError(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                _structureState.value = StructureState.Error(errorMsg)
                onError(errorMsg)
            }
        }
    }

    /**
     * Добавить элемент в структуру.
     */
    fun addElement(
        element: ScaffoldElement,
        onSuccess: (UpdateResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val sessionId = currentSessionId ?: run {
            onError("No active session")
            return
        }

        _structureState.value = StructureState.Updating

        viewModelScope.launch {
            try {
                val action = UpdateAction(
                    action = "ADD",
                    element_data = element
                )

                val response = apiService.updateStructure(sessionId, action)

                if (response.isSuccessful && response.body() != null) {
                    val updateResponse = response.body()!!
                    _structureState.value = StructureState.Updated(updateResponse)
                    onSuccess(updateResponse)
                } else {
                    val errorMsg = "Server error: ${response.code()}"
                    _structureState.value = StructureState.Error(errorMsg)
                    onError(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                _structureState.value = StructureState.Error(errorMsg)
                onError(errorMsg)
            }
        }
    }
}

enum class EditMode {
    EDIT,
    SIMULATION
}

sealed class StructureState {
    object Idle : StructureState()
    object Updating : StructureState()
    data class Updated(val response: UpdateResponse) : StructureState()
    data class Error(val message: String) : StructureState()
}
