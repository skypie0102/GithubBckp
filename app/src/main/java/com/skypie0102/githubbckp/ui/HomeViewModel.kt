package com.skypie0102.githubbckp.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.backup.GithubMirrorRestorePublisher
import com.skypie0102.githubbckp.backup.MirrorRestoreCoordinator
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.data.local.toEntity
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubGateway
import com.skypie0102.githubbckp.storage.StorageDestination
import com.skypie0102.githubbckp.storage.StoragePreferences
import com.skypie0102.githubbckp.storage.drive.GoogleDriveAuthManager
import com.skypie0102.githubbckp.worker.BackupCadence
import com.skypie0102.githubbckp.worker.BackupScheduleSettings
import com.skypie0102.githubbckp.worker.BackupScheduler
import com.skypie0102.githubbckp.worker.ScheduledBackupRunOutcome
import com.skypie0102.githubbckp.worker.ScheduledBackupRunProgress
import com.skypie0102.githubbckp.worker.ScheduledBackupRunStatus
import com.skypie0102.githubbckp.worker.summarizeScheduledBackupRun
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GithubRestoreTargetMode {
    NEW_REPOSITORY,
    EXISTING_EMPTY_REPOSITORY,
}

data class HomeUiState(
    val githubConnected: Boolean = false,
    val driveConnected: Boolean = false,
    val driveOauthConfigurationHint: String = "",
    val storageDestination: StorageDestination = StorageDestination.GOOGLE_DRIVE,
    val documentTreeConfigured: Boolean = false,
    val documentTreeName: String? = null,
    val scheduleEnabled: Boolean = false,
    val scheduleCadence: BackupCadence = BackupCadence.DAILY,
    val scheduledRunStatus: ScheduledBackupRunStatus? = null,
    val scheduledRunProgress: ScheduledBackupRunProgress? = null,
    val repositories: List<RepositoryEntity> = emptyList(),
    val backupHealth: BackupHealthSummary = BackupHealthSummary(),
    val recentBackups: List<BackupEntity> = emptyList(),
    val restoredMirrors: List<MirrorRestoreRecord> = emptyList(),
    val githubPublishRestoreId: String? = null,
    val githubRestoreTargetMode: GithubRestoreTargetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
    val githubPublishRepositoryName: String = "",
    val githubPublishExistingRepository: String = "",
    val githubPublishPrivate: Boolean = true,
    val lastPublishedRepositoryUrl: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backupDao: BackupDao,
    private val mirrorDao: MirrorDao,
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val driveAuthManager: GoogleDriveAuthManager,
    private val storagePreferences: StoragePreferences,
    private val backupScheduler: BackupScheduler,
    private val mirrorRestoreCoordinator: MirrorRestoreCoordinator,
    private val githubRestorePublisher: GithubMirrorRestorePublisher,
) : ViewModel() {
    private val initialSchedule = backupScheduler.scheduleSettings()
    private var mirrorHealthState: List<MirrorEntity> = emptyList()
    private val _state = MutableStateFlow(
        HomeUiState(
            githubConnected = githubAuthManager.isAuthenticated(),
            driveConnected = driveAuthManager.isAuthenticated(),
            driveOauthConfigurationHint = driveAuthManager.oauthConfigurationHint(),
            storageDestination = storagePreferences.destination(),
            documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
            documentTreeName = storagePreferences.documentTreeDisplayName(),
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
            backupDao.observeRecentBackups(limit = 20).collect { backups ->
                _state.update { it.copy(recentBackups = backups) }
            }
        }
        viewModelScope.launch {
            backupScheduler.observeScheduledRunStatus().collectLatest { status ->
                _state.update {
                    it.copy(
                        scheduledRunStatus = status,
                        scheduledRunProgress = null,
                    )
                }
                val currentStatus = status ?: return@collectLatest
                if (currentStatus.outcome != ScheduledBackupRunOutcome.QUEUED) return@collectLatest
                val runId = currentStatus.scheduledRunId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@collectLatest

                backupDao.observeBackupsForScheduledRun(runId).collect { backups ->
                    val progress = summarizeScheduledBackupRun(currentStatus, backups)
                    _state.update { current ->
                        if (current.scheduledRunStatus?.scheduledRunId == runId) {
                            current.copy(scheduledRunProgress = progress)
                        } else {
                            current
                        }
                    }
                }
            }
        }
        viewModelScope.launch { refreshRestores() }
    }

    fun refreshRepositories() {
        viewModelScope.launch { runBusy { refreshRepositoriesInternal() } }
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

    fun setScheduleEnabled(enabled: Boolean) {
        val state = _state.value
        if (enabled) {
            when {
                !state.githubConnected -> {
                    _state.update { it.copy(message = "Connect GitHub before enabling automatic backups") }
                    return
                }
                state.storageDestination == StorageDestination.GOOGLE_DRIVE && !state.driveConnected -> {
                    _state.update { it.copy(message = "Connect Google Drive or choose a backup folder first") }
                    return
                }
                state.storageDestination == StorageDestination.DOCUMENT_TREE && !state.documentTreeConfigured -> {
                    _state.update { it.copy(message = "Choose a backup folder before enabling automatic backups") }
                    return
                }
            }
        }
        saveSchedule(
            BackupScheduleSettings(
                enabled = enabled,
                cadence = state.scheduleCadence,
            ),
            message = if (enabled) "Automatic mirror updates enabled" else "Automatic backups disabled",
        )
    }

    fun setScheduleCadence(cadence: BackupCadence) {
        val state = _state.value
        saveSchedule(
            BackupScheduleSettings(
                enabled = state.scheduleEnabled,
                cadence = cadence,
            ),
            message = "Automatic mirror cadence set to ${cadence.displayName()}",
        )
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
                val resultData = data ?: error(
                    "Google Drive authorization returned no data. Check ${driveAuthManager.oauthConfigurationHint()} in the Google Android OAuth client.",
                )
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
        _state.update {
            it.copy(
                message = "Google Drive authorization did not complete. The Android OAuth client must match ${driveAuthManager.oauthConfigurationHint()}.",
            )
        }
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
        _state.update { it.copy(storageDestination = StorageDestination.GOOGLE_DRIVE, message = "Google Drive selected") }
    }

    fun useBackupFolder() {
        if (!_state.value.documentTreeConfigured) {
            _state.update { it.copy(message = "Choose a backup folder first") }
            return
        }
        storagePreferences.setDestination(StorageDestination.DOCUMENT_TREE)
        _state.update { it.copy(storageDestination = StorageDestination.DOCUMENT_TREE, message = "Backup folder selected") }
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
                backupScheduler.enqueue(repositoryIds = selected.map { it.githubId })
                _state.update {
                    it.copy(message = "Queued ${selected.size} repository backup${if (selected.size == 1) "" else "s"}")
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
                _state.update { it.copy(message = "Restored ${record.archiveName}: ${record.refCount} refs verified") }
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

    fun beginGithubPublish(restore: MirrorRestoreRecord) {
        val baseName = restore.archiveName
            .removeSuffix(".mirror.zip")
            .removeSuffix(".zip")
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-')
            .ifBlank { "restored-repository" }
        _state.update {
            it.copy(
                githubPublishRestoreId = restore.id,
                githubRestoreTargetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
                githubPublishRepositoryName = baseName.take(100),
                githubPublishExistingRepository = "",
                githubPublishPrivate = true,
                lastPublishedRepositoryUrl = null,
                message = null,
            )
        }
    }

    fun setGithubRestoreTargetMode(mode: GithubRestoreTargetMode) {
        _state.update { it.copy(githubRestoreTargetMode = mode, message = null) }
    }

    fun setGithubPublishRepositoryName(value: String) {
        _state.update { it.copy(githubPublishRepositoryName = value.take(100)) }
    }

    fun setGithubPublishExistingRepository(value: String) {
        _state.update { it.copy(githubPublishExistingRepository = value.take(200)) }
    }

    fun setGithubPublishPrivate(value: Boolean) {
        _state.update { it.copy(githubPublishPrivate = value) }
    }

    fun cancelGithubPublish() {
        _state.update {
            it.copy(
                githubPublishRestoreId = null,
                githubRestoreTargetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
                githubPublishRepositoryName = "",
                githubPublishExistingRepository = "",
                lastPublishedRepositoryUrl = null,
            )
        }
    }

    fun publishRestoreToGithub() {
        val state = _state.value
        val restoreId = state.githubPublishRestoreId ?: return
        if (!state.githubConnected) {
            _state.update { it.copy(message = "Connect GitHub before publishing a restored mirror") }
            return
        }
        when (state.githubRestoreTargetMode) {
            GithubRestoreTargetMode.NEW_REPOSITORY -> {
                if (state.githubPublishRepositoryName.isBlank()) {
                    _state.update { it.copy(message = "Enter a repository name") }
                    return
                }
            }
            GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY -> {
                if (!isRepositoryFullName(state.githubPublishExistingRepository)) {
                    _state.update { it.copy(message = "Enter the existing target as owner/repository") }
                    return
                }
            }
        }

        viewModelScope.launch {
            runBusy {
                val result = when (state.githubRestoreTargetMode) {
                    GithubRestoreTargetMode.NEW_REPOSITORY -> githubRestorePublisher.publishToNewRepository(
                        restoreId = restoreId,
                        repositoryName = state.githubPublishRepositoryName,
                        isPrivate = state.githubPublishPrivate,
                    )
                    GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY ->
                        githubRestorePublisher.publishToExistingEmptyRepository(
                            restoreId = restoreId,
                            repositoryFullName = state.githubPublishExistingRepository,
                        )
                }
                refreshRepositoriesInternal()
                _state.update {
                    it.copy(
                        githubPublishRestoreId = null,
                        githubRestoreTargetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
                        githubPublishRepositoryName = "",
                        githubPublishExistingRepository = "",
                        lastPublishedRepositoryUrl = result.repositoryUrl,
                        message = buildString {
                            append("Restored ${result.pushedRefCount} Git refs to ${result.repositoryFullName}")
                            if (result.restoredLfsObjectCount > 0) {
                                append("; ${result.restoredLfsObjectCount} LFS object")
                                if (result.restoredLfsObjectCount != 1) append("s")
                            }
                            if (result.restoredReleaseCount > 0) {
                                append("; ${result.restoredReleaseCount} release")
                                if (result.restoredReleaseCount != 1) append("s")
                                append(" / ${result.restoredReleaseAssetCount} asset")
                                if (result.restoredReleaseAssetCount != 1) append("s")
                            }
                            if (result.skippedReadOnlyRefs.isNotEmpty()) {
                                append("; skipped ${result.skippedReadOnlyRefs.size} read-only pull-request refs")
                            }
                        },
                    )
                }
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
                repository.toEntity(selectedForBackup = existing[repository.id]?.selectedForBackup ?: true)
            },
        )
        _state.update {
            it.copy(
                githubConnected = true,
                message = "Found ${remote.size} repositories",
            )
        }
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

    private fun isRepositoryFullName(value: String): Boolean {
        val parts = value.trim().split('/')
        return parts.size == 2 && parts.all { it.isNotBlank() }
    }
}

private fun BackupCadence.displayName(): String = when (this) {
    BackupCadence.DAILY -> "daily"
    BackupCadence.WEEKLY -> "weekly"
}
