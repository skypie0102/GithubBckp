package com.skypie0102.githubbckp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.backup.BackupCoordinator
import com.skypie0102.githubbckp.backup.BackupOrigin
import com.skypie0102.githubbckp.backup.BackupRequest
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.data.local.toRepositoryRef
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
        val origin = inputData.getString(KEY_BACKUP_ORIGIN)
            ?.let { value -> runCatching { BackupOrigin.valueOf(value) }.getOrNull() }
        val scheduledRunId = inputData.getString(KEY_SCHEDULED_RUN_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            BackupWorkerDependencies::class.java,
        )
        val repository = dependencies.backupDao().getRepository(repositoryId)
            ?: return Result.success()

        setForeground(createForegroundInfo(repositoryId, repository.owner, repository.name))

        val success = dependencies.backupCoordinator().run(
            BackupRequest(
                repository = repository.toRepositoryRef(),
                origin = origin,
                scheduledRunId = scheduledRunId,
            ),
        )
        if (!success) {
            val currentRepository = dependencies.backupDao().getRepository(repositoryId)
            if (currentRepository?.selectedForBackup == true) {
                val latestAttempt = dependencies.backupDao().getLatestBackup(repositoryId)
                dependencies.backupProblemNotifier().notifyBackupFailure(
                    repository = currentRepository,
                    errorMessage = latestAttempt?.errorMessage,
                )
            }
        }
        return if (success) Result.success() else Result.failure()
    }

    private fun createForegroundInfo(repositoryId: Long, owner: String, name: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active backups",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Long-running GitHub mirror backups"
            },
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up GitHub repository")
            .setContentText("$owner/$name")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(
            foregroundNotificationId(repositoryId),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_REPOSITORY_ID = "repository_id"
        const val KEY_BACKUP_ORIGIN = "backup_origin"
        const val KEY_SCHEDULED_RUN_ID = "scheduled_run_id"
        private const val CHANNEL_ID = "active-backups"
        private const val FOREGROUND_NOTIFICATION_BASE = 0x4A000000

        private fun foregroundNotificationId(repositoryId: Long): Int =
            FOREGROUND_NOTIFICATION_BASE xor repositoryId.hashCode()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerDependencies {
    fun backupDao(): BackupDao
    fun backupCoordinator(): BackupCoordinator
    fun backupProblemNotifier(): BackupProblemNotifier
}
