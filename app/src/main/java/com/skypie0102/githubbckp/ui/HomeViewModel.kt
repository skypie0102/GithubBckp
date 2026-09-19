package com.skypie0102.githubbckp.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skypie0102.githubbckp.data.local.RepositoryDao
import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.LatestReleaseDao
import com.skypie0102.githubbckp.data.local.LatestReleaseEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.data.local.toEntity
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubGateway
import com.skypie0102.githubbckp.github.GithubRepositoryAccessVerifier
import com.skypie0102.githubbckp.mirror.MirrorStorageReconciler
import com.skypie0102.githubbckp.storage.StoragePreferences
import com.skypie0102.githubbckp.worker.ActiveBackupNotificationManager
import com.skypie0102.githubbckp.worker.BackupCadence
import com.skypie0102.githubbckp.worker.BackupScheduleSettings
import com.skypie0102.githubbckp.worker.BackupScheduler
import com.skypie0102.githubbckp.worker.ScheduledBackupRunStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeUiState(
    val githubConnected: Boolean = false,
    val documentTreeConfigured: Boolean = false,
    val documentTreeName: String? = null,
    val notificationsReady: Boolean = false,
    val scheduleEnabled: Boolean = false,
    val scheduleCadence: BackupCadence = BackupCadence.DAILY,
    val scheduledRunStatus: ScheduledBackupRunStatus? = null,
    val repositories: List<RepositoryEntity> = emptyList(),
    val latestReleases: Map<Long, LatestReleaseEntity> = emptyMap(),
    val backupHealth: BackupHealthSummary = BackupHealthSummary(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repositoryDao: RepositoryDao,
    private val mirrorDao: MirrorDao,
    private val latestReleaseDao: LatestReleaseDao,
    private val githubAuthManager: GithubAuthManager,
    private val githubGateway: GithubGateway,
    private val githubRepositoryAccessVerifier: GithubRepositoryAccessVerifier,
    private val mirrorStorageReconciler: MirrorStorageReconciler,
    private val storagePreferences: StoragePreferences,
    private val backupScheduler: BackupScheduler,
    private val activeBackupNotificationManager: ActiveBackupNotificationManager,
) : ViewModel() {
    private val initialSchedule = backupScheduler.scheduleSettings()
    private var mirrorHealthState: List<MirrorEntity> = emptyList()
    private var remoteRefsDigests: Map<Long, String> = emptyMap()

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
        viewModelScope.launch { mirrorStorageReconciler.reconcileAll() }

        viewModelScope.launch {
            repositoryDao.observeRepositories().collect { repositories ->
                _state.update { current ->
                    current.copy(
                        repositories = repositories,
                        backupHealth = summarizeBackupHealth(
                            repositories = repositories,
                            mirrors = mirrorHealthState,
                            scheduleEnabled = current.scheduleEnabled,
                            cadence = current.scheduleCadence,
                            nowEpochMs = System.currentTimeMillis(),
                            globalBlockMessage = current.globalBackupBlockMessage(),
                            remoteRefsDigests = remoteRefsDigests,
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
                            globalBlockMessage = current.globalBackupBlockMessage(),
                            remoteRefsDigests = remoteRefsDigests,
                        ),
                    )
                }
            }
        }

        viewModelScope.launch {
            latestReleaseDao.observeAll().collect { releases ->
                _state.update { current ->
                    current.copy(
                        latestReleases = releases.associateBy { it.repositoryId },
                    )
                }
            }
        }

        viewModelScope.launch {
            backupScheduler.observeScheduledRunStatus().collect { status ->
                _state.update { it.copy(scheduledRunStatus = status) }
            }
        }

        if (githubAuthManager.isAuthenticated()) {
            refreshRemoteHealth()
        }
    }

    private fun refreshRemoteHealth() {
        viewModelScope.launch {
            val repositories = repositoryDao.getRepositories()
                .filter { it.isAvailable && it.selectedForBackup }
            if (repositories.isEmpty()) return@launch

            val limiter = Semaphore(REPOSITORY_ACCESS_CHECK_CONCURRENCY)
            val remoteStates = coroutineScope {
                repositories.map { repository ->
                    async(Dispatchers.IO) {
                        limiter.withPermit {
                            val remoteUrl = "https://github.com/${repository.owner}/${repository.name}.git"
                            repository.githubId to githubRepositoryAccessVerifier.inspectRemote(remoteUrl)
                        }
                    }
                }.awaitAll()
            }

            remoteRefsDigests = remoteStates.mapNotNull { (repositoryId, remoteState) ->
                remoteState.refsDigest?.let { repositoryId to it }
            }.toMap()

            _state.update { current ->
                current.copy(
                    backupHealth = summarizeBackupHealth(
                        repositories = current.repositories,
                        mirrors = mirrorHealthState,
                        scheduleEnabled = current.scheduleEnabled,
                        cadence = current.scheduleCadence,
                        nowEpochMs = System.currentTimeMillis(),
                        globalBlockMessage = current.globalBackupBlockMessage(),
                        remoteRefsDigests = remoteRefsDigests,
                    ),
                )
            }
        }
    }

    fun refreshReadiness() {
        _state.update { current ->
            val refreshed = current.copy(
                githubConnected = githubAuthManager.isAuthenticated(),
                documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
                documentTreeName = storagePreferences.documentTreeDisplayName(),
                notificationsReady = activeBackupNotificationManager.isReady(),
            )
            refreshed.copy(
                backupHealth = summarizeBackupHealth(
                    repositories = refreshed.repositories,
                    mirrors = mirrorHealthState,
                    scheduleEnabled = refreshed.scheduleEnabled,
                    cadence = refreshed.scheduleCadence,
                    nowEpochMs = System.currentTimeMillis(),
                    globalBlockMessage = refreshed.globalBackupBlockMessage(),
                    remoteRefsDigests = remoteRefsDigests,
                ),
            )
        }
        viewModelScope.launch { mirrorStorageReconciler.reconcileAll() }
    }

    fun refreshRepositories() {
        refreshRepositories(showMessage = true)
    }

    private fun refreshRepositories(showMessage: Boolean) {
        viewModelScope.launch {
            runBusy {
                val existing = repositoryDao.getRepositories().associateBy { it.githubId }
                val remote = githubGateway.listRepositories()
                val limiter = Semaphore(REPOSITORY_ACCESS_CHECK_CONCURRENCY)
                val inspected = coroutineScope {
                    remote.map { repository ->
                        async(Dispatchers.IO) {
                            limiter.withPermit {
                                val remoteState = githubRepositoryAccessVerifier.inspect(repository)
                                repository.toEntity(
                                    selectedForBackup = existing[repository.id]?.selectedForBackup ?: false,
                                    isAvailable = remoteState.readable,
                                ) to remoteState.refsDigest
                            }
                        }
                    }.awaitAll()
                }
                val verified = inspected.map { it.first }
                remoteRefsDigests = inspected.mapNotNull { (repository, digest) ->
                    digest?.let { repository.githubId to it }
                }.toMap()
                repositoryDao.upsertRepositories(verified)

                val unavailableCount = verified.count { !it.isAvailable }
                _state.update { current ->
                    current.copy(
                        githubConnected = true,
                        backupHealth = summarizeBackupHealth(
                            repositories = verified,
                            mirrors = mirrorHealthState,
                            scheduleEnabled = current.scheduleEnabled,
                            cadence = current.scheduleCadence,
                            nowEpochMs = System.currentTimeMillis(),
                            globalBlockMessage = current.globalBackupBlockMessage(),
                            remoteRefsDigests = remoteRefsDigests,
                        ),
                        message = if (showMessage) {
                            buildString {
                                append("Checked ${remote.size} repositories")
                                if (unavailableCount > 0) {
                                    append("; ")
                                    append(unavailableCount)
                                    append(" cannot be read with this token")
                                }
                            }
                        } else {
                            current.message
                        },
                    )
                }
            }
        }
    }

    fun setRepositorySelected(repositoryId: Long, selected: Boolean) {
        viewModelScope.launch {
            repositoryDao.setRepositorySelected(repositoryId, selected)
            if (selected) refreshRemoteHealth()
        }
    }

    fun setAllRepositoriesSelected(selected: Boolean) {
        viewModelScope.launch {
            repositoryDao.setAvailableRepositoriesSelected(selected)
            if (selected) refreshRemoteHealth()
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
                _state.update { current ->
                    val refreshed = current.copy(
                        documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
                        documentTreeName = storagePreferences.documentTreeDisplayName(),
                        message = "Local backup folder selected",
                    )
                    refreshed.copy(
                        backupHealth = summarizeBackupHealth(
                            repositories = refreshed.repositories,
                            mirrors = mirrorHealthState,
                            scheduleEnabled = refreshed.scheduleEnabled,
                            cadence = refreshed.scheduleCadence,
                            nowEpochMs = System.currentTimeMillis(),
                            globalBlockMessage = refreshed.globalBackupBlockMessage(),
                            remoteRefsDigests = remoteRefsDigests,
                        ),
                    )
                }
                mirrorStorageReconciler.reconcileAll()
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

    fun updateAvailableRepositories() {
        val repositoryIds = _state.value.backupHealth.updateAvailableRepositories
            .map { it.repositoryId }

        enqueueRepositories(
            repositoryIds = repositoryIds,
            emptyMessage = "No selected repositories have updates available",
            queuedLabel = "update",
        )
    }

    fun backupRepositoriesWithoutMirror() {
        val repositoryIds = _state.value.backupHealth.repositories
            .filter {
                it.state == RepositoryBackupHealthState.NEVER_BACKED_UP ||
                    it.state == RepositoryBackupHealthState.MISSING
            }
            .map { it.repositoryId }

        enqueueRepositories(
            repositoryIds = repositoryIds,
            emptyMessage = "All selected repositories already have a local mirror",
            queuedLabel = "backup",
        )
    }

    private fun enqueueRepositories(
        repositoryIds: List<Long>,
        emptyMessage: String,
        queuedLabel: String,
    ) {
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
            repositoryIds.isEmpty() ->
                _state.update { it.copy(message = emptyMessage) }
            else -> {
                backupScheduler.enqueue(repositoryIds)
                _state.update {
                    it.copy(
                        notificationsReady = true,
                        message = "Queued ${repositoryIds.size} repository $queuedLabel" +
                            if (repositoryIds.size == 1) "" else "s",
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
                    globalBlockMessage = current.globalBackupBlockMessage(),
                    remoteRefsDigests = remoteRefsDigests,
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

private fun HomeUiState.globalBackupBlockMessage(): String? = when {
    !githubConnected -> "GitHub is not connected."
    !documentTreeConfigured -> "The local backup folder is unavailable."
    !notificationsReady -> "Notifications must be enabled so running backups remain visible."
    else -> null
}

private const val REPOSITORY_ACCESS_CHECK_CONCURRENCY = 4

private fun BackupCadence.displayName(): String = when (this) {
    BackupCadence.DAILY -> "daily"
    BackupCadence.WEEKLY -> "weekly"
}
