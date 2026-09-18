package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.data.local.RepositoryDao
import com.skypie0102.githubbckp.data.local.MirrorDao
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.mirror.MirrorAttemptStatus
import com.skypie0102.githubbckp.mirror.MirrorSyncCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class RepositoryBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val repositoryId = inputData.getLong(KEY_REPOSITORY_ID, -1L)
        if (repositoryId < 0) return Result.failure()

        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            BackupWorkerDependencies::class.java,
        )
        val repository = dependencies.repositoryDao().getRepository(repositoryId)
            ?: return Result.success()

        val notifications = dependencies.activeBackupNotificationManager()
        if (!notifications.isReady()) {
            val previous = dependencies.mirrorDao().get(repositoryId)
            dependencies.mirrorDao().upsert(
                (previous ?: MirrorEntity(repositoryId = repositoryId)).copy(
                    lastAttemptAtEpochMs = System.currentTimeMillis(),
                    lastAttemptStatus = MirrorAttemptStatus.BLOCKED.name,
                    lastWarning = NOTIFICATIONS_REQUIRED_MESSAGE,
                    lastError = null,
                ),
            )
            return Result.failure()
        }

        setForeground(
            notifications.foregroundInfo(
                workId = id,
                repositoryId = repositoryId,
                owner = repository.owner,
                name = repository.name,
            ),
        )

        val success = dependencies.mirrorSyncCoordinator().sync(repository) { stage ->
            setForeground(
                notifications.foregroundInfo(
                    workId = id,
                    repositoryId = repositoryId,
                    owner = repository.owner,
                    name = repository.name,
                    stage = stage,
                ),
            )
        }

        if (!success) {
            val currentRepository = dependencies.repositoryDao().getRepository(repositoryId)
            if (currentRepository?.selectedForBackup == true) {
                val state = dependencies.mirrorDao().get(repositoryId)
                dependencies.backupProblemNotifier().notifyBackupFailure(
                    repository = currentRepository,
                    errorMessage = state?.lastError,
                )
            }
        }

        return if (success) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_REPOSITORY_ID = "repository_id"

        const val NOTIFICATIONS_REQUIRED_MESSAGE =
            "Notifications must be enabled so every running backup remains visible."
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerDependencies {
    fun repositoryDao(): RepositoryDao
    fun mirrorDao(): MirrorDao
    fun mirrorSyncCoordinator(): MirrorSyncCoordinator
    fun activeBackupNotificationManager(): ActiveBackupNotificationManager
    fun backupProblemNotifier(): BackupProblemNotifier
}
