package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.data.local.BackupDao
import com.skypie0102.githubbckp.storage.StoragePreferences
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
            ScheduledBackupDependencies::class.java,
        )
        if (!dependencies.storagePreferences().isConfigured()) return Result.success()

        val repositories = dependencies.backupDao().getSelectedRepositories()
        if (repositories.isNotEmpty()) {
            dependencies.backupScheduler().enqueueScheduled(repositories.map { it.githubId })
        }
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledBackupDependencies {
    fun backupDao(): BackupDao
    fun backupScheduler(): BackupScheduler
    fun storagePreferences(): StoragePreferences
}
