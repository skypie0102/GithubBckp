package com.skypie0102.githubbckp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.backup.MirrorStatus
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.data.local.toEntity
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubGateway
import com.skypie0102.githubbckp.storage.StoragePreferences
import com.skypie0102.githubbckp.worker.BackupCadence
import com.skypie0102.githubbckp.worker.BackupScheduleSettings
import com.skypie0102.githubbckp.worker.BackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val githubConnected: Boolean = false,
    val storageConfigured: Boolean = false,
    val storageName: String? = null,
    val scheduleEnabled: Boolean = false,
    val scheduleCadence: BackupCadence = BackupCadence.DAILY,
    val repositories: List<RepositoryEntity> = emptyList(),
    val mirrors: List<MirrorEntity> = emptyList(),
    val backupHealth: BackupHealthSummary = BackupHealthSummary(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backupDao: BackupDao,
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val storagePreferences: StoragePreferences,
    private val backupScheduler: BackupScheduler,
) : ViewModel() {
    private val initialSchedule = backupScheduler.settings()

    private val _state = MutableStateFlow(
        HomeUiState(
            githubConnected = githubAuthManager.isAuthenticated(),
            storageConfigured = storagePreferences.isConfigured(),
            storageName = storagePreferences.documentTreeDisplayName(),
            scheduleEnabled = initialSchedule.enabled,
            scheduleCadence = initialSchedule.cadence,
        ),
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        backupScheduler.reconcileSchedule()

        viewModelScope.launch {
            backupDao.observeRepositories().collect { repositories ->
                _state.update { current ->
                    withHealth(current.copy(repositories = repositories))
                }
            }
        }
        viewModelScope.launch {
            backupDao.observeMirrors().collect { mirrors ->
                _state.update { current ->
                    withHealth(current.copy(mirrors = mirrors))
                }
            }
        }

        if (githubAuthManager.isAuthenticated()) {
            viewModelScope.launch {
                runBusy {
                    refreshRepositoriesInternal(showMessage = false)
                }
            }
        }
    }

    fun refreshRepositories() {
        viewModelScope.launch {
            runBusy { refreshRepositoriesInternal(showMessage = true) }
        }
    }

    fun chooseBackupFolder(uri: Uri) {
        viewModelScope.launch {
            runBusy {
                storagePreferences.persistDocumentTree(uri)
                _state.update {
                    it.copy(
                        storageConfigured = true,
                        storageName = storagePreferences.documentTreeDisplayName(),
                        message = "Local backup folder selected",
                    )
                }
            }
        }
    }

    fun backupFolderSelectionCancelled() {
        _state.update { it.copy(message = "Backup folder selection was cancelled") }
    }

    fun setRepositorySelected(repositoryId: Long, selected: Boolean) {
        viewModelScope.launch {
            backupDao.setRepositorySelected(repositoryId, selected)
        }
    }

    fun setAllRepositoriesSelected(selected: Boolean) {
        viewModelScope.launch {
            backupDao.setAllRepositoriesSelected(selected)
            _state.update {
                it.copy(
                    message = if (selected) "Selected all repositories" else "Cleared repository selection",
                )
            }
        }
    }

    fun backupSelectedRepositories() {
        val current = _state.value
        val selected = current.repositories.filter { it.selectedForBackup }
        when {
            !current.githubConnected -> {
                _state.update { it.copy(message = "Connect GitHub first") }
                return
            }
            !current.storageConfigured -> {
                _state.update { it.copy(message = "Choose a local backup folder first") }
                return
            }
            selected.isEmpty() -> {
                _state.update { it.copy(message = "Select at least one repository") }
                return
            }
        }

        viewModelScope.launch {
            selected.forEach { repository ->
                val previous = backupDao.getMirror(repository.githubId)
                    ?: MirrorEntity(repositoryId = repository.githubId)
                backupDao.upsertMirror(
                    previous.copy(
                        status = MirrorStatus.QUEUED,
                        lastError = null,
                    ),
                )
            }
            backupScheduler.enqueueManual(selected.map { it.githubId })
            _state.update {
                it.copy(
                    message = "Queued ${selected.size} repository backup${if (selected.size == 1) "" else "s"}",
                )
            }
        }
    }

    fun setScheduleEnabled(enabled: Boolean) {
        val current = _state.value
        if (enabled && !current.githubConnected) {
            _state.update { it.copy(message = "Connect GitHub before enabling automatic backups") }
            return
        }
        if (enabled && !current.storageConfigured) {
            _state.update { it.copy(message = "Choose a local backup folder before enabling automatic backups") }
            return
        }

        saveSchedule(
            BackupScheduleSettings(enabled = enabled, cadence = current.scheduleCadence),
            if (enabled) "Automatic backups enabled" else "Automatic backups disabled",
        )
    }

    fun setScheduleCadence(cadence: BackupCadence) {
        val current = _state.value
        saveSchedule(
            BackupScheduleSettings(enabled = current.scheduleEnabled, cadence = cadence),
            "Automatic backup cadence set to ${cadence.name.lowercase()}",
        )
    }

    private fun saveSchedule(settings: BackupScheduleSettings, message: String) {
        backupScheduler.updateSchedule(settings)
        _state.update { current ->
            withHealth(
                current.copy(
                    scheduleEnabled = settings.enabled,
                    scheduleCadence = settings.cadence,
                    message = message,
                ),
            )
        }
    }

    private suspend fun refreshRepositoriesInternal(showMessage: Boolean) {
        val existing = backupDao.getAllRepositories().associateBy { it.githubId }
        val remote = githubGateway.listRepositories()
        backupDao.replaceRepositoryInventory(
            remote.map { repository ->
                repository.toEntity(
                    selectedForBackup = existing[repository.id]?.selectedForBackup ?: true,
                )
            },
        )
        _state.update {
            it.copy(
                githubConnected = true,
                message = if (showMessage) "Found ${remote.size} repositories" else it.message,
            )
        }
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (throwable: Throwable) {
            _state.update {
                it.copy(message = throwable.message ?: throwable.javaClass.simpleName)
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun withHealth(state: HomeUiState): HomeUiState =
        state.copy(
            backupHealth = summarizeBackupHealth(
                repositories = state.repositories,
                mirrors = state.mirrors,
                scheduleEnabled = state.scheduleEnabled,
                cadence = state.scheduleCadence,
            ),
        )
}
