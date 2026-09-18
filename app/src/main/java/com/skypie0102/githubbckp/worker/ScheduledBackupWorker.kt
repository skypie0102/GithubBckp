package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.storage.StoragePreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID

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

        val repositoryIds = dependencies.backupDao()
            .getAvailableRepositories()
            .asSequence()
            .filter { it.selectedForBackup }
            .map { it.githubId }
            .toList()

        if (repositoryIds.isEmpty()) {
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
            githubAuthenticated = dependencies.githubAuthManager().isAuthenticated(),
            documentTreeConfigured = dependencies.storagePreferences().isDocumentTreeConfigured(),
            notificationsReady = dependencies.activeBackupNotificationManager().isReady(),
        )
        if (!readiness.ready) {
            schedulePreferences.saveRunStatus(
                scheduledBackupRunStatus(
                    completedAtEpochMs = System.currentTimeMillis(),
                    repositoryCount = repositoryIds.size,
                    scheduledRunId = scheduledRunId,
                    readiness = readiness,
                ),
            )
            return Result.success()
        }

        dependencies.backupScheduler().enqueueScheduled(
            repositoryIds = repositoryIds,
            scheduledRunId = scheduledRunId,
        )
        schedulePreferences.saveRunStatus(
            scheduledBackupRunStatus(
                completedAtEpochMs = System.currentTimeMillis(),
                repositoryCount = repositoryIds.size,
                scheduledRunId = scheduledRunId,
                readiness = readiness,
            ),
        )
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledBackupWorkerDependencies {
    fun backupDao(): BackupDao
    fun backupScheduler(): BackupScheduler
    fun schedulePreferences(): BackupSchedulePreferences
    fun githubAuthManager(): GithubAuthManager
    fun storagePreferences(): StoragePreferences
    fun activeBackupNotificationManager(): ActiveBackupNotificationManager
}
