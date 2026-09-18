package com.skypie0102.githubbckp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.backup.BackupOrigin
import com.skypie0102.githubbckp.backup.MirrorBackupCoordinator
import com.skypie0102.githubbckp.backup.MirrorBackupRequest
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
        if (repositoryId < 0L) return Result.failure()

        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            RepositoryBackupDependencies::class.java,
        )
        val repository = dependencies.backupDao().getRepository(repositoryId)
            ?: return Result.success()

        val origin = inputData.getString(KEY_BACKUP_ORIGIN)
            ?.let { runCatching { BackupOrigin.valueOf(it) }.getOrNull() }
            ?: BackupOrigin.MANUAL

        setForeground(createForegroundInfo(repositoryId, repository.owner, repository.name))

        val success = dependencies.mirrorBackupCoordinator().run(
            MirrorBackupRequest(
                repository = repository.toRepositoryRef(),
                origin = origin,
            ),
        )
        return if (success) Result.success() else Result.failure()
    }

    private fun createForegroundInfo(repositoryId: Long, owner: String, name: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Mirror backups",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Ongoing GitHub repository mirror backups"
                    },
                )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Backing up GitHub repository")
            .setContentText("$owner/$name")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(repositoryId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId(repositoryId), notification)
        }
    }

    companion object {
        const val KEY_REPOSITORY_ID = "repository_id"
        const val KEY_BACKUP_ORIGIN = "backup_origin"
        private const val CHANNEL_ID = "mirror-backups"
        private const val NOTIFICATION_BASE = 0x4A000000

        private fun notificationId(repositoryId: Long): Int =
            NOTIFICATION_BASE xor repositoryId.hashCode()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RepositoryBackupDependencies {
    fun backupDao(): BackupDao
    fun mirrorBackupCoordinator(): MirrorBackupCoordinator
}
