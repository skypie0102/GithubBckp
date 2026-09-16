package com.skypie0102.githubbckp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.backup.BackupReverificationService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupReverificationUiState(
    val busyBackupId: Long? = null,
)

@HiltViewModel
class BackupReverificationViewModel @Inject constructor(
    private val reverificationService: BackupReverificationService,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupReverificationUiState())
    val state: StateFlow<BackupReverificationUiState> = _state.asStateFlow()

    fun reverifyBackup(backupId: Long) {
        if (_state.value.busyBackupId != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyBackupId = backupId) }
            try {
                reverificationService.reverify(backupId)
            } finally {
                _state.update { it.copy(busyBackupId = null) }
            }
        }
    }
}
