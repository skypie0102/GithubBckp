package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryDao
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubRepositoryAccessVerifier
import com.skypie0102.githubbckp.mirror.MirrorAttemptStatus
import com.skypie0102.githubbckp.storage.StoragePreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ScheduledBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            ScheduledBackupWorkerDependencies::class.java,
        )
        val schedulePreferences = dependencies.schedulePreferences()
        val settings = schedulePreferences.settings()
        if (!settings.enabled) return Result.success()
        val scheduledRunId = UUID.randomUUID().toString()

        val repositories = dependencies.repositoryDao()
            .getAvailableRepositories()
            .filter { it.selectedForBackup }

        if (repositories.isEmpty()) {
            schedulePreferences.saveRunStatus(
                scheduledBackupRunStatus(
                    completedAtEpochMs = System.currentTimeMillis(),
                    repositoryCount = 0,
                    scheduledRunId = scheduledRunId,
                ),
            )
            return Result.success()
        }

        val readiness = evaluateScheduledBackupReadiness(
            githubAuthenticated = dependencies.githubAuthManager().hasValidAccessToken(),
            documentTreeConfigured = dependencies.storagePreferences().isDocumentTreeConfigured(),
            notificationsReady = dependencies.activeBackupNotificationManager().isReady(),
        )
        if (!readiness.ready) {
            schedulePreferences.saveRunStatus(
                scheduledBackupRunStatus(
                    completedAtEpochMs = System.currentTimeMillis(),
                    repositoryCount = repositories.size,
                    scheduledRunId = scheduledRunId,
                    readiness = readiness,
                ),
            )
            return Result.success()
        }

        val verifier = dependencies.githubRepositoryAccessVerifier()
        val access = verifyRepositoryReadAccess(
            repositories = repositories,
            accessCheck = { repository ->
                verifier.canReadRemote(repositoryRemoteUrl(repository))
            },
        )
        val blocked = access.filterNot { it.readable }.map { it.repository }
        val readable = access.filter { it.readable }.map { it.repository }

        val blockedAt = System.currentTimeMillis()
        blocked.forEach { repository ->
            val previous = dependencies.mirrorDao().get(repository.githubId)
            dependencies.mirrorDao().upsert(
                (previous ?: MirrorEntity(repositoryId = repository.githubId)).copy(
                    lastAttemptAtEpochMs = blockedAt,
                    lastAttemptStatus = MirrorAttemptStatus.BLOCKED.name,
                    lastWarning = REPOSITORY_ACCESS_MESSAGE,
                    lastError = null,
                ),
            )
        }

        if (readable.isEmpty()) {
            val blockedReadiness = ScheduledBackupReadiness(
                ready = false,
                blockReason = ScheduledBackupBlockReason.REPOSITORY_ACCESS_UNAVAILABLE,
            )
            schedulePreferences.saveRunStatus(
                scheduledBackupRunStatus(
                    completedAtEpochMs = System.currentTimeMillis(),
                    repositoryCount = repositories.size,
                    scheduledRunId = scheduledRunId,
                    readiness = blockedReadiness,
                ),
            )
            return Result.success()
        }

        dependencies.backupScheduler().enqueueScheduled(readable.map { it.githubId })
        schedulePreferences.saveRunStatus(
            scheduledBackupRunStatus(
                completedAtEpochMs = System.currentTimeMillis(),
                repositoryCount = readable.size,
                scheduledRunId = scheduledRunId,
                readiness = readiness,
            ),
        )
        return Result.success()
    }

    private companion object {
        const val ACCESS_CHECK_CONCURRENCY = 4
        const val REPOSITORY_ACCESS_MESSAGE =
            "Repository cannot be read with the current GitHub token. Check fine-grained repository access or organization approval."
    }
}

internal data class RepositoryReadAccess(
    val repository: RepositoryEntity,
    val readable: Boolean,
)

internal suspend fun verifyRepositoryReadAccess(
    repositories: List<RepositoryEntity>,
    accessCheck: suspend (RepositoryEntity) -> Boolean,
): List<RepositoryReadAccess> {
    val limiter = Semaphore(4)
    return coroutineScope {
        repositories.map { repository ->
            async(Dispatchers.IO) {
                RepositoryReadAccess(
                    repository = repository,
                    readable = limiter.withPermit {
                        accessCheck(repository)
                    },
                )
            }
        }.awaitAll()
    }
}

internal fun repositoryRemoteUrl(repository: RepositoryEntity): String =
    "https://github.com/${repository.owner}/${repository.name}.git"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledBackupWorkerDependencies {
    fun repositoryDao(): RepositoryDao
    fun mirrorDao(): MirrorDao
    fun backupScheduler(): BackupScheduler
    fun schedulePreferences(): BackupSchedulePreferences
    fun githubAuthManager(): GithubAuthManager
    fun githubRepositoryAccessVerifier(): GithubRepositoryAccessVerifier
    fun storagePreferences(): StoragePreferences
    fun activeBackupNotificationManager(): ActiveBackupNotificationManager
}
