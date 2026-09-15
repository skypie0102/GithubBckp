package com.skypie0102.githubbckp.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.backup.MirrorRestoreCoordinator
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.data.local.toEntity
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubDeviceSession
import com.skypie0102.githubbckp.github.GithubGateway
import com.skypie0102.githubbckp.storage.StorageDestination
import com.skypie0102.githubbckp.storage.StoragePreferences
import com.skypie0102.githubbckp.storage.drive.GoogleDriveAuthManager
import com.skypie0102.githubbckp.worker.BackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val githubConfigured: Boolean = false,
    val githubConnected: Boolean = false,
    val driveConnected: Boolean = false,
    val storageDestination: StorageDestination = StorageDestination.GOOGLE_DRIVE,
    val documentTreeConfigured: Boolean = false,
    val documentTreeName: String? = null,
    val backupType: BackupType = BackupType.SOURCE_ARCHIVE,
    val githubDeviceSession: GithubDeviceSession? = null,
    val repositories: List<RepositoryEntity> = emptyList(),
    val recentBackups: List<BackupEntity> = emptyList(),
    val restoredMirrors: List<MirrorRestoreRecord> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backupDao: BackupDao,
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val driveAuthManager: GoogleDriveAuthManager,
    private val storagePreferences: StoragePreferences,
    private val backupScheduler: BackupScheduler,
    private val mirrorRestoreCoordinator: MirrorRestoreCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(
        HomeUiState(
            githubConfigured = githubAuthManager.isConfigured(),
            githubConnected = githubAuthManager.isAuthenticated(),
            driveConnected = driveAuthManager.isAuthenticated(),
            storageDestination = storagePreferences.destination(),
            documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
            documentTreeName = storagePreferences.documentTreeDisplayName(),
        ),
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            backupDao.observeRepositories().collect { repositories ->
                _state.update { it.copy(repositories = repositories) }
            }
        }
        viewModelScope.launch {
            backupDao.observeRecentBackups(limit = 20).collect { backups ->
                _state.update { it.copy(recentBackups = backups) }
            }
        }
        viewModelScope.launch { refreshRestores() }
    }

    fun connectGithub() {
        if (_state.value.busy) return
        viewModelScope.launch {
            runBusy {
                val session = githubAuthManager.startDeviceFlow()
                _state.update { it.copy(githubDeviceSession = session, message = null) }
                githubAuthManager.pollUntilAuthorized(session)
                _state.update {
                    it.copy(
                        githubConnected = true,
                        githubDeviceSession = null,
                        message = "GitHub connected",
                    )
                }
                refreshRepositoriesInternal()
            }
        }
    }

    fun refreshRepositories() {
        viewModelScope.launch { runBusy { refreshRepositoriesInternal() } }
    }

    fun setRepositorySelected(repositoryId: Long, selected: Boolean) {
        viewModelScope.launch { backupDao.setRepositorySelected(repositoryId, selected) }
    }

    fun setBackupType(type: BackupType) {
        _state.update { it.copy(backupType = type) }
    }

    fun connectDrive(onResolution: (PendingIntent) -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            runBusy {
                val result = driveAuthManager.beginAuthorization()
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                        ?: error("Google Drive authorization did not provide a resolution")
                    onResolution(pendingIntent)
                } else {
                    storagePreferences.setDestination(StorageDestination.GOOGLE_DRIVE)
                    _state.update {
                        it.copy(
                            driveConnected = true,
                            storageDestination = StorageDestination.GOOGLE_DRIVE,
                            message = "Google Drive connected and selected",
                        )
                    }
                }
            }
        }
    }

    fun completeDriveAuthorization(data: Intent?) {
        viewModelScope.launch {
            runBusy {
                val resultData = data ?: error("Google Drive authorization returned no data")
                driveAuthManager.completeAuthorization(resultData)
                storagePreferences.setDestination(StorageDestination.GOOGLE_DRIVE)
                _state.update {
                    it.copy(
                        driveConnected = true,
                        storageDestination = StorageDestination.GOOGLE_DRIVE,
                        message = "Google Drive connected and selected",
                    )
                }
            }
        }
    }

    fun driveAuthorizationCancelled() {
        _state.update { it.copy(message = "Google Drive authorization was cancelled") }
    }

    fun chooseBackupFolder(uri: Uri) {
        viewModelScope.launch {
            runBusy {
                storagePreferences.persistDocumentTree(uri)
                _state.update {
                    it.copy(
                        storageDestination = StorageDestination.DOCUMENT_TREE,
                        documentTreeConfigured = true,
                        documentTreeName = storagePreferences.documentTreeDisplayName(),
                        message = "Backup folder selected",
                    )
                }
            }
        }
    }

    fun backupFolderSelectionCancelled() {
        _state.update { it.copy(message = "Backup folder selection was cancelled") }
    }

    fun useGoogleDrive() {
        if (!_state.value.driveConnected) {
            _state.update { it.copy(message = "Connect Google Drive first") }
            return
        }
        storagePreferences.setDestination(StorageDestination.GOOGLE_DRIVE)
        _state.update {
            it.copy(storageDestination = StorageDestination.GOOGLE_DRIVE, message = "Google Drive selected")
        }
    }

    fun useBackupFolder() {
        if (!_state.value.documentTreeConfigured) {
            _state.update { it.copy(message = "Choose a backup folder first") }
            return
        }
        storagePreferences.setDestination(StorageDestination.DOCUMENT_TREE)
        _state.update {
            it.copy(storageDestination = StorageDestination.DOCUMENT_TREE, message = "Backup folder selected")
        }
    }

    fun backupSelectedRepositories() {
        val state = _state.value
        val selected = state.repositories.filter { it.selectedForBackup }
        when {
            !state.githubConnected -> _state.update { it.copy(message = "Connect GitHub first") }
            state.storageDestination == StorageDestination.GOOGLE_DRIVE && !state.driveConnected ->
                _state.update { it.copy(message = "Connect Google Drive or choose a backup folder") }
            state.storageDestination == StorageDestination.DOCUMENT_TREE && !state.documentTreeConfigured ->
                _state.update { it.copy(message = "Choose a backup folder first") }
            selected.isEmpty() -> _state.update { it.copy(message = "Select at least one repository") }
            else -> {
                backupScheduler.enqueue(
                    repositoryIds = selected.map { it.githubId },
                    type = state.backupType,
                )
                _state.update {
                    it.copy(
                        message = "Queued ${selected.size} ${state.backupType.displayName()} backup${if (selected.size == 1) "" else "s"}",
                    )
                }
            }
        }
    }

    fun restoreMirrorArchive(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            runBusy {
                val record = mirrorRestoreCoordinator.restore(uri)
                refreshRestores()
                _state.update {
                    it.copy(
                        message = "Restored ${record.archiveName}: ${record.refCount} refs verified",
                    )
                }
            }
        }
    }

    fun restoreArchiveSelectionCancelled() {
        _state.update { it.copy(message = "Mirror restore selection was cancelled") }
    }

    fun deleteRestoredMirror(id: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            runBusy {
                mirrorRestoreCoordinator.deleteRestore(id)
                refreshRestores()
                _state.update { it.copy(message = "Restored mirror deleted") }
            }
        }
    }

    private suspend fun refreshRestores() {
        _state.update { it.copy(restoredMirrors = mirrorRestoreCoordinator.listRestores()) }
    }

    private suspend fun refreshRepositoriesInternal() {
        val existing = backupDao.getRepositories().associateBy { it.githubId }
        val remote = githubGateway.listRepositories()
        backupDao.upsertRepositories(
            remote.map { repository ->
                repository.toEntity(
                    selectedForBackup = existing[repository.id]?.selectedForBackup ?: true,
                )
            },
        )
        _state.update { it.copy(githubConnected = true, message = "Found ${remote.size} repositories") }
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (throwable: Throwable) {
            _state.update { it.copy(message = throwable.message ?: throwable.javaClass.simpleName) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}

private fun BackupType.displayName(): String = when (this) {
    BackupType.SOURCE_ARCHIVE -> "source snapshot"
    BackupType.GIT_MIRROR -> "Git mirror"
}
