package com.bililive.danmaku.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bililive.danmaku.domain.model.AppSettings
import com.bililive.danmaku.domain.repository.SettingsRepository
import com.bililive.danmaku.domain.usecase.ValidateRoomIdUseCase
import com.bililive.danmaku.domain.usecase.ValidationResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RoomInputUiState(
    val roomInput: String = "",
    val inputError: String? = null,
    val recentRooms: List<Long> = emptyList(),
    val settings: AppSettings = AppSettings()
)

class RoomInputViewModel(
    private val settingsRepository: SettingsRepository,
    private val validateRoomIdUseCase: ValidateRoomIdUseCase
) : ViewModel() {

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val validateRoomIdUseCase: ValidateRoomIdUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RoomInputViewModel::class.java))
            return RoomInputViewModel(settingsRepository, validateRoomIdUseCase) as T
        }
    }

    private val _uiState = MutableStateFlow(RoomInputUiState())
    val uiState: StateFlow<RoomInputUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        loadRecentRooms()
    }

    fun onRoomInputChange(value: String) {
        _uiState.update { it.copy(roomInput = value, inputError = null) }
    }

    fun validateInput(): Long? {
        val result = validateRoomIdUseCase(_uiState.value.roomInput)
        return when (result) {
            is ValidationResult.Valid -> result.roomId
            is ValidationResult.Invalid -> {
                _uiState.update { it.copy(inputError = result.message) }
                null
            }
        }
    }

    fun loadRecentRooms() {
        viewModelScope.launch {
            val rooms = settingsRepository.getRecentRooms()
            _uiState.update { it.copy(recentRooms = rooms) }
        }
    }

    fun clearRecentRooms() {
        viewModelScope.launch {
            settingsRepository.clearRecentRooms()
            _uiState.update { it.copy(recentRooms = emptyList()) }
        }
    }

    fun clearInput() {
        _uiState.update { it.copy(roomInput = "", inputError = null) }
    }
}
