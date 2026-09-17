package com.skypie0102.githubbckp.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.backup.DisasterRecoveryDrillResult
import com.skypie0102.githubbckp.backup.DisasterRecoveryDrillService
import com.skypie0102.githubbckp.backup.GithubMirrorRestorePublisher
import com.skypie0102.githubbckp.backup.MirrorRestoreCoordinator
import com.skypie0102.githubbckp.backup.MirrorRestoreRecord
import com.skypie0102.githubbckp.backup.RetentionPreferences
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
    val githubConfigured: Boolean = false,
    val githubConnected: Boolean = false,
    val githubWorkflowPermission: Boolean = false,
    val driveConnected: Boolean = false,
    val storageDestination: StorageDestination = StorageDestination.GOOGLE_DRIVE,
    val documentTreeConfigured: Boolean = false,
    val documentTreeName: String? = null,
    // Kept temporarily for UI/database compatibility; new backups are mirror-only.
    val backupType: BackupType = BackupType.GIT_MIRROR,
    val scheduleEnabled: Boolean = false,
    val scheduleCadence: BackupCadence = BackupCadence.DAILY,
    val scheduledBackupType: BackupType = BackupType.GIT_MIRROR,
    val scheduledRunStatus: ScheduledBackupRunStatus? = null,
    val scheduledRunProgress: ScheduledBackupRunProgress? = null,
    val retentionKeepCount: Int = RetentionPreferences.KEEP_ALL,
    val githubDeviceSession: GithubDeviceSession? = null,
    val repositories: List<RepositoryEntity> = emptyList(),
    val backupHealth: BackupHealthSummary = BackupHealthSummary(),
    val recentBackups: List<BackupEntity> = emptyList(),
    val restoredMirrors: List<MirrorRestoreRecord> = emptyList(),
    val disasterRecoveryDrillResults: Map<String, DisasterRecoveryDrillResult> = emptyMap(),
    val githubPublishRestoreId: String? = null,
    val githubPublishIsDrill: Boolean = false,
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
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val driveAuthManager: GoogleDriveAuthManager,
    private val storagePreferences: StoragePreferences,
    private val backupScheduler: BackupScheduler,
    private val mirrorRestoreCoordinator: MirrorRestoreCoordinator,
    private val retentionPreferences: RetentionPreferences,
    private val githubRestorePublisher: GithubMirrorRestorePublisher,
    private val disasterRecoveryDrillService: DisasterRecoveryDrillService,
) : ViewModel() {
    private val initialSchedule = backupScheduler.scheduleSettings()
    private var backupHealthHistory: List<BackupEntity> = emptyList()
    private val _state = MutableStateFlow(
        HomeUiState(
            githubConfigured = githubAuthManager.isConfigured(),
            githubConnected = githubAuthManager.isAuthenticated(),
            githubWorkflowPermission = githubAuthManager.hasWorkflowScopeCached(),
            driveConnected = driveAuthManager.isAuthenticated(),
            storageDestination = storagePreferences.destination(),
            documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
            documentTreeName = storagePreferences.documentTreeDisplayName(),
            backupType = BackupType.GIT_MIRROR,
            scheduleEnabled = initialSchedule.enabled,
            scheduleCadence = initialSchedule.cadence,
            scheduledBackupType = BackupType.GIT_MIRROR,
            scheduledRunStatus = backupScheduler.scheduledRunStatus(),
            retentionKeepCount = retentionPreferences.keepCount(),
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
                            backups = backupHealthHistory,
                            scheduleEnabled = current.scheduleEnabled,
                            cadence = current.scheduleCadence,
                            nowEpochMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            backupDao.observeBackupHealthHistory().collect { backups ->
                backupHealthHistory = backups
                _state.update { current ->
                    current.copy(
                        backupHealth = summarizeBackupHealth(
                            repositories = current.repositories,
                            backups = backups,
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
                        githubWorkflowPermission = githubAuthManager.hasWorkflowScopeCached(),
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

    fun setBackupType(type: BackupType) {
        if (type != BackupType.GIT_MIRROR) {
            _state.update { it.copy(backupType = BackupType.GIT_MIRROR, message = "Source snapshots were removed; backups use Git mirrors only") }
            return
        }
        _state.update { it.copy(backupType = BackupType.GIT_MIRROR) }
    }

    fun setRetentionKeepCount(count: Int) {
        // Legacy control kept until the old UI is removed. A one-mirror model
        // supersedes retention, so always keep the logical current object only.
        retentionPreferences.setKeepCount(RetentionPreferences.KEEP_ALL)
        _state.update {
            it.copy(
                retentionKeepCount = RetentionPreferences.KEEP_ALL,
                message = "Retention copies were removed; each repository now keeps one current mirror",
            )
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

    fun setScheduledBackupType(type: BackupType) {
        if (type != BackupType.GIT_MIRROR) {
            _state.update {
                it.copy(
                    scheduledBackupType = BackupType.GIT_MIRROR,
                    message = "Snapshot schedules were removed; automatic backups update the Git mirror",
                )
            }
        }
    }

    private fun saveSchedule(settings: BackupScheduleSettings, message: String) {
        backupScheduler.updateSchedule(settings)
        _state.update { current ->
            current.copy(
                scheduleEnabled = settings.enabled,
                scheduleCadence = settings.cadence,
                scheduledBackupType = BackupType.GIT_MIRROR,
                backupHealth = summarizeBackupHealth(
                    repositories = current.repositories,
                    backups = backupHealthHistory,
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
                message = "Google Drive authorization was cancelled. If account selection closes unexpectedly, verify ${driveAuthManager.oauthConfigurationHint()}.",
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
                    it.copy(message = "Queued ${selected.size} Git mirror update${if (selected.size == 1) "" else "s"}")
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
                disasterRecoveryDrillService.deleteResult(id)
                mirrorRestoreCoordinator.deleteRestore(id)
                refreshRestores()
                _state.update { it.copy(message = "Restored mirror deleted") }
            }
        }
    }

    fun beginGithubPublish(restore: MirrorRestoreRecord) {
        beginGithubPublishInternal(restore, isDrill = false)
    }

    fun beginDisasterRecoveryDrill(restore: MirrorRestoreRecord) {
        beginGithubPublishInternal(restore, isDrill = true)
    }

    private fun beginGithubPublishInternal(restore: MirrorRestoreRecord, isDrill: Boolean) {
        val baseName = restore.archiveName
            .removeSuffix(".mirror.zip")
            .removeSuffix(".zip")
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .trim('-')
            .ifBlank { "restored-repository" }
        val suggested = if (isDrill) "$baseName-drill" else baseName
        _state.update {
            it.copy(
                githubPublishRestoreId = restore.id,
                githubPublishIsDrill = isDrill,
                githubRestoreTargetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
                githubPublishRepositoryName = suggested.take(100),
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
        _state.update { current ->
            current.copy(githubPublishPrivate = if (current.githubPublishIsDrill) true else value)
        }
    }

    fun cancelGithubPublish() {
        _state.update {
            it.copy(
                githubPublishRestoreId = null,
                githubPublishIsDrill = false,
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
        if (!state.githubWorkflowPermission) {
            _state.update {
                it.copy(
                    message = "Update GitHub permissions and approve workflow access before publishing a restored mirror",
                )
            }
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
                if (state.githubPublishIsDrill) {
                    val drillResult = when (state.githubRestoreTargetMode) {
                        GithubRestoreTargetMode.NEW_REPOSITORY ->
                            disasterRecoveryDrillService.runToNewPrivateRepository(
                                restoreId = restoreId,
                                repositoryName = state.githubPublishRepositoryName,
                            )
                        GithubRestoreTargetMode.EXISTING_EMPTY_REPOSITORY ->
                            disasterRecoveryDrillService.runToExistingEmptyPrivateRepository(
                                restoreId = restoreId,
                                repositoryFullName = state.githubPublishExistingRepository,
                            )
                    }
                    refreshRepositoriesInternal()
                    refreshRestores()
                    _state.update {
                        it.copy(
                            githubPublishRestoreId = null,
                            githubPublishIsDrill = false,
                            githubRestoreTargetMode = GithubRestoreTargetMode.NEW_REPOSITORY,
                            githubPublishRepositoryName = "",
                            githubPublishExistingRepository = "",
                            githubPublishPrivate = true,
                            lastPublishedRepositoryUrl = drillResult.repositoryUrl,
                            message = buildString {
                                append("Recovery drill passed for ${drillResult.repositoryFullName}: ")
                                append("${drillResult.verifiedGitRefCount} Git refs verified")
                                if (drillResult.verifiedLfsObjectCount > 0) {
                                    append("; ${drillResult.verifiedLfsObjectCount} LFS objects verified")
                                }
                                if (drillResult.verifiedReleaseCount > 0) {
                                    append("; ${drillResult.verifiedReleaseCount} releases / ")
                                    append("${drillResult.verifiedReleaseAssetCount} assets verified")
                                }
                            },
                        )
                    }
                    return@runBusy
                }

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
                        githubPublishIsDrill = false,
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
        val restores = mirrorRestoreCoordinator.listRestores()
        val drillResults = restores.mapNotNull { restore ->
            disasterRecoveryDrillService.latestResult(restore.id)?.let { restore.id to it }
        }.toMap()
        _state.update {
            it.copy(
                restoredMirrors = restores,
                disasterRecoveryDrillResults = drillResults,
            )
        }
    }

    private suspend fun refreshRepositoriesInternal() {
        val existing = backupDao.getRepositories().associateBy { it.githubId }
        val remote = githubGateway.listRepositories()
        backupDao.upsertRepositories(
            remote.map { repository ->
                repository.toEntity(selectedForBackup = existing[repository.id]?.selectedForBackup ?: true)
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

    private fun isRepositoryFullName(value: String): Boolean {
        val parts = value.trim().split('/')
        return parts.size == 2 && parts.all { it.isNotBlank() }
    }
}

private fun BackupCadence.displayName(): String = when (this) {
    BackupCadence.DAILY -> "daily"
    BackupCadence.WEEKLY -> "weekly"
}
