package com.skypie0102.githubbckp.ui

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.data.local.toEntity
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubDeviceSession
import com.skypie0102.githubbckp.github.GithubGateway
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
    val githubDeviceSession: GithubDeviceSession? = null,
    val repositories: List<RepositoryEntity> = emptyList(),
    val recentBackups: List<BackupEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backupDao: BackupDao,
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val driveAuthManager: GoogleDriveAuthManager,
    private val backupScheduler: BackupScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(
        HomeUiState(
            githubConfigured = githubAuthManager.isConfigured(),
            githubConnected = githubAuthManager.isAuthenticated(),
            driveConnected = driveAuthManager.isAuthenticated(),
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
        viewModelScope.launch {
            runBusy { refreshRepositoriesInternal() }
        }
    }

    fun setRepositorySelected(repositoryId: Long, selected: Boolean) {
        viewModelScope.launch {
            backupDao.setRepositorySelected(repositoryId, selected)
        }
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
                    _state.update {
                        it.copy(driveConnected = true, message = "Google Drive connected")
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
                _state.update {
                    it.copy(driveConnected = true, message = "Google Drive connected")
                }
            }
        }
    }

    fun driveAuthorizationCancelled() {
        _state.update { it.copy(message = "Google Drive authorization was cancelled") }
    }

    fun backupSelectedRepositories() {
        val selected = _state.value.repositories.filter { it.selectedForBackup }
        when {
            !_state.value.githubConnected -> {
                _state.update { it.copy(message = "Connect GitHub first") }
            }
            !_state.value.driveConnected -> {
                _state.update { it.copy(message = "Connect Google Drive first") }
            }
            selected.isEmpty() -> {
                _state.update { it.copy(message = "Select at least one repository") }
            }
            else -> {
                backupScheduler.enqueue(selected.map { it.githubId })
                _state.update {
                    it.copy(message = "Queued ${selected.size} repository backup${if (selected.size == 1) "" else "s"}")
                }
            }
        }
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
            _state.update {
                it.copy(message = throwable.message ?: throwable.javaClass.simpleName)
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
