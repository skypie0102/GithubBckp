package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.storage.StoragePreferences
import com.skypie0102.githubbckp.storage.drive.GoogleDriveAuthManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class ScheduledBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            ScheduledBackupWorkerDependencies::class.java,
        )
        val settings = dependencies.schedulePreferences().settings()
        if (!settings.enabled) return Result.success()

        val repositoryIds = dependencies.backupDao()
            .getAvailableRepositories()
            .asSequence()
            .filter { it.selectedForBackup }
            .map { it.githubId }
            .toList()
        if (repositoryIds.isEmpty()) return Result.success()

        val storagePreferences = dependencies.storagePreferences()
        val readiness = evaluateScheduledBackupReadiness(
            githubAuthenticated = dependencies.githubAuthManager().isAuthenticated(),
            destination = storagePreferences.destination(),
            driveAuthenticated = dependencies.googleDriveAuthManager().isAuthenticated(),
            documentTreeConfigured = storagePreferences.isDocumentTreeConfigured(),
        )
        if (!readiness.ready) return Result.success()

        dependencies.backupScheduler().enqueueScheduled(
            repositoryIds = repositoryIds,
            type = settings.backupType,
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
    fun googleDriveAuthManager(): GoogleDriveAuthManager
}
