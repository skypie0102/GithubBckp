package com.skypie0102.githubbckp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.backup.MirrorRestoreCoordinator
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.backup.RecoveryDrillCoordinator
import com.skypie0102.githubbckp.backup.RecoveryDrillRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecoveryDrillUiState(
    val restores: List<MirrorRestoreRecord> = emptyList(),
    val drills: List<RecoveryDrillRecord> = emptyList(),
    val selectedRestore: MirrorRestoreRecord? = null,
    val targetMode: GithubRestoreTargetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
    val repositoryName: String = "",
    val existingRepository: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val lastRepositoryUrl: String? = null,
)

@HiltViewModel
class RecoveryDrillViewModel @Inject constructor(
    private val coordinator: RecoveryDrillCoordinator,
    private val restoreCoordinator: MirrorRestoreCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(RecoveryDrillUiState())
    val state: StateFlow<RecoveryDrillUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val drillResult = runCatching { coordinator.listDrills() }
            val restoreResult = runCatching { restoreCoordinator.listRestores() }
            _state.update { current ->
                current.copy(
                    restores = restoreResult.getOrElse { current.restores },
                    drills = drillResult.getOrElse { current.drills },
                    message = restoreResult.exceptionOrNull()?.message
                        ?: drillResult.exceptionOrNull()?.message
                        ?: current.message,
                )
            }
        }
    }

    fun beginDrill(restore: MirrorRestoreRecord) {
        val suggested = restore.archiveName
            .removeSuffix(".mirror.zip")
            .removeSuffix(".zip")
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-')
            .take(80)
            .ifBlank { "recovery-drill" }
            .let { "$it-drill" }
            .take(100)
        _state.update {
            it.copy(
                selectedRestore = restore,
                targetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
                repositoryName = suggested,
                existingRepository = "",
                message = null,
                lastRepositoryUrl = null,
            )
        }
    }

    fun cancelDrill() {
        if (_state.value.busy) return
        _state.update {
            it.copy(
                selectedRestore = null,
                targetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
                repositoryName = "",
                existingRepository = "",
                message = null,
            )
        }
    }

    fun setTargetMode(mode: GithubRestoreTargetMode) {
        if (_state.value.busy) return
        _state.update { it.copy(targetMode = mode, message = null) }
    }

    fun setRepositoryName(value: String) {
        if (_state.value.busy) return
        _state.update { it.copy(repositoryName = value.take(100), message = null) }
    }

    fun setExistingRepository(value: String) {
        if (_state.value.busy) return
        _state.update { it.copy(existingRepository = value.take(200), message = null) }
    }

    fun runDrill() {
        val current = _state.value
        val restore = current.selectedRestore ?: return
        when (current.targetMode) {
            GithubRestoreTargetMode.NEW_REPOSITORY -> if (current.repositoryName.isBlank()) {
                _state.update { it.copy(message = "Enter a private drill repository name") }
                return
            }
            GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY -> if (!isRepositoryFullName(current.existingRepository)) {
                _state.update { it.copy(message = "Enter the private empty target as owner/repository") }
                return
            }
        }

        viewModelScope.launch {
            runBusy {
                val result = when (current.targetMode) {
                    GithubRestoreTargetMode.NEW_REPOSITORY -> coordinator.runToNewPrivateRepository(
                        restore = restore,
                        repositoryName = current.repositoryName,
                    )
                    GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY ->
                        coordinator.runToExistingPrivateEmptyRepository(
                            restore = restore,
                            repositoryFullName = current.existingRepository,
                        )
                }
                val drills = coordinator.listDrills()
                _state.update {
                    it.copy(
                        drills = drills,
                        selectedRestore = null,
                        repositoryName = "",
                        existingRepository = "",
                        lastRepositoryUrl = result.repositoryUrl,
                        message = result.message,
                    )
                }
            }
        }
    }

    fun exportAudit(drillId: String, destination: Uri) {
        viewModelScope.launch {
            runBusy {
                coordinator.exportAuditReport(drillId, destination)
                _state.update { it.copy(message = "Recovery drill audit exported") }
            }
        }
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        try {
            block()
        } catch (throwable: Exception) {
            if (throwable is CancellationException) throw throwable
            _state.update { it.copy(message = throwable.message ?: throwable.javaClass.simpleName) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun isRepositoryFullName(value: String): Boolean {
        val parts = value.trim().split('/')
        return parts.size == 2 && parts.all { it.isNotBlank() }
    }
}
