package com.skypie0102.githubbckp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.data.local.toEntity
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubGateway
import com.skypie0102.githubbckp.storage.StoragePreferences
import com.skypie0102.githubbckp.worker.ActiveBackupNotificationManager
import com.skypie0102.githubbckp.worker.BackupCadence
import com.skypie0102.githubbckp.worker.BackupScheduleSettings
import com.skypie0102.githubbckp.worker.BackupScheduler
import com.skypie0102.githubbckp.worker.ScheduledBackupRunStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val githubConnected: Boolean = false,
    val documentTreeConfigured: Boolean = false,
    val documentTreeName: String? = null,
    val notificationsReady: Boolean = false,
    val scheduleEnabled: Boolean = false,
    val scheduleCadence: BackupCadence = BackupCadence.DAILY,
    val scheduledRunStatus: ScheduledBackupRunStatus? = null,
    val repositories: List<RepositoryEntity> = emptyList(),
    val backupHealth: BackupHealthSummary = BackupHealthSummary(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backupDao: BackupDao,
    private val mirrorDao: MirrorDao,
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val storagePreferences: StoragePreferences,
    private val backupScheduler: BackupScheduler,
    private val activeBackupNotificationManager: ActiveBackupNotificationManager,
) : ViewModel() {
    private val initialSchedule = backupScheduler.scheduleSettings()
    private var mirrorHealthState: List<MirrorEntity> = emptyList()

    private val _state = MutableStateFlow(
        HomeUiState(
            githubConnected = githubAuthManager.isAuthenticated(),
            documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
            documentTreeName = storagePreferences.documentTreeDisplayName(),
            notificationsReady = activeBackupNotificationManager.isReady(),
            scheduleEnabled = initialSchedule.enabled,
            scheduleCadence = initialSchedule.cadence,
            scheduledRunStatus = backupScheduler.scheduledRunStatus(),
        ),
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        backupScheduler.reconcileSchedule()

        viewModelScope.launch {
            backupDao.observeRepositories().collect { repositories ->
                _state.update { current ->
                    current.copy(
                        repositories = repositories,
                        backupHealth = summarizeBackupHealth(
                            repositories = repositories,
                            mirrors = mirrorHealthState,
                            scheduleEnabled = current.scheduleEnabled,
                            cadence = current.scheduleCadence,
                            nowEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        viewModelScope.launch {
            mirrorDao.observeAll().collect { mirrors ->
                mirrorHealthState = mirrors
                _state.update { current ->
                    current.copy(
                        backupHealth = summarizeBackupHealth(
                            repositories = current.repositories,
                            mirrors = mirrors,
                            scheduleEnabled = current.scheduleEnabled,
                            cadence = current.scheduleCadence,
                            nowEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        viewModelScope.launch {
            backupScheduler.observeScheduledRunStatus().collect { status ->
                _state.update { it.copy(scheduledRunStatus = status) }
            }
        }
    }

    fun refreshReadiness() {
        _state.update {
            it.copy(
                githubConnected = githubAuthManager.isAuthenticated(),
                documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
                documentTreeName = storagePreferences.documentTreeDisplayName(),
                notificationsReady = activeBackupNotificationManager.isReady(),
            )
        }
    }

    fun refreshRepositories() {
        viewModelScope.launch {
            runBusy {
                val existing = backupDao.getRepositories().associateBy { it.githubId }
                val remote = githubGateway.listRepositories()
                backupDao.upsertRepositories(
                    remote.map { repository ->
                        repository.toEntity(
                            selectedForBackup = existing[repository.id]?.selectedForBackup ?: true,
                        )
                    },
                )
                _state.update {
                    it.copy(
                        githubConnected = true,
                        message = "Found ${remote.size} repositories",
                    )
                }
            }
        }
    }

    fun setRepositorySelected(repositoryId: Long, selected: Boolean) {
        viewModelScope.launch { backupDao.setRepositorySelected(repositoryId, selected) }
    }

    fun setAllRepositoriesSelected(selected: Boolean) {
        viewModelScope.launch {
            backupDao.setAvailableRepositoriesSelected(selected)
            _state.update {
                it.copy(
                    message = if (selected) {
                        "Selected all available repositories"
                    } else {
                        "Cleared repository selection"
                    },
                )
            }
        }
    }

    fun chooseBackupFolder(uri: Uri) {
        viewModelScope.launch {
            runBusy {
                storagePreferences.persistDocumentTree(uri)
                _state.update {
                    it.copy(
                        documentTreeConfigured = true,
                        documentTreeName = storagePreferences.documentTreeDisplayName(),
                        message = "Local backup folder selected",
                    )
                }
            }
        }
    }

    fun backupFolderSelectionCancelled() {
        _state.update { it.copy(message = "Backup folder selection was cancelled") }
    }

    fun setScheduleEnabled(enabled: Boolean) {
        val current = _state.value
        if (enabled) {
            when {
                !current.githubConnected -> {
                    _state.update { it.copy(message = "Connect GitHub before enabling automatic updates") }
                    return
                }
                !current.documentTreeConfigured -> {
                    _state.update { it.copy(message = "Choose a local backup folder first") }
                    return
                }
                !activeBackupNotificationManager.isReady() -> {
                    _state.update {
                        it.copy(
                            notificationsReady = false,
                            message = "Enable notifications before enabling automatic updates",
                        )
                    }
                    return
                }
            }
        }

        saveSchedule(
            BackupScheduleSettings(
                enabled = enabled,
                cadence = current.scheduleCadence,
            ),
            message = if (enabled) "Automatic mirror updates enabled" else "Automatic updates disabled",
        )
    }

    fun setScheduleCadence(cadence: BackupCadence) {
        val current = _state.value
        saveSchedule(
            BackupScheduleSettings(
                enabled = current.scheduleEnabled,
                cadence = cadence,
            ),
            message = "Automatic mirror cadence set to ${cadence.displayName()}",
        )
    }

    fun backupSelectedRepositories() {
        val current = _state.value
        val selected = current.repositories.filter { it.isAvailable && it.selectedForBackup }

        when {
            !githubAuthManager.isAuthenticated() ->
                _state.update { it.copy(message = "Connect GitHub first") }
            !storagePreferences.isDocumentTreeConfigured() ->
                _state.update { it.copy(message = "Choose a local backup folder first") }
            !activeBackupNotificationManager.isReady() ->
                _state.update {
                    it.copy(
                        notificationsReady = false,
                        message = "Enable notifications before starting backups",
                    )
                }
            selected.isEmpty() ->
                _state.update { it.copy(message = "Select at least one repository") }
            else -> {
                backupScheduler.enqueue(selected.map { it.githubId })
                _state.update {
                    it.copy(
                        notificationsReady = true,
                        message = "Queued ${selected.size} repository update${if (selected.size == 1) "" else "s"}",
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun saveSchedule(settings: BackupScheduleSettings, message: String) {
        backupScheduler.updateSchedule(settings)
        _state.update { current ->
            current.copy(
                scheduleEnabled = settings.enabled,
                scheduleCadence = settings.cadence,
                backupHealth = summarizeBackupHealth(
                    repositories = current.repositories,
                    mirrors = mirrorHealthState,
                    scheduleEnabled = settings.enabled,
                    cadence = settings.cadence,
                    nowEpochMs = System.currentTimeMillis(),
                ),
                message = message,
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
}

private fun BackupCadence.displayName(): String = when (this) {
    BackupCadence.DAILY -> "daily"
    BackupCadence.WEEKLY -> "weekly"
}
