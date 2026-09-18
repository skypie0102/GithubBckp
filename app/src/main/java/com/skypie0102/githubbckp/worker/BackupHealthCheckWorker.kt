package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.data.local.RepositoryDao
import com.skypie0102.githubbckp.data.local.MirrorDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

class BackupHealthCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            BackupHealthCheckWorkerDependencies::class.java,
        )
        val schedulePreferences = dependencies.schedulePreferences()
        val settings = schedulePreferences.settings()
        if (!settings.enabled) {
            dependencies.backupProblemNotifier().notifyOverdueBackups(emptyList())
            return Result.success()
        }

        val repositories = dependencies.repositoryDao().getAvailableRepositories()
        val mirrors = dependencies.mirrorDao().observeAll().first()
        val overdue = findOverdueBackupRepositories(
            repositories = repositories,
            mirrors = mirrors,
            settings = settings,
            scheduleEnabledAtEpochMs = schedulePreferences.enabledAtEpochMs(),
            nowEpochMs = System.currentTimeMillis(),
        )
        dependencies.backupProblemNotifier().notifyOverdueBackups(overdue)
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupHealthCheckWorkerDependencies {
    fun repositoryDao(): RepositoryDao
    fun mirrorDao(): MirrorDao
    fun schedulePreferences(): BackupSchedulePreferences
    fun backupProblemNotifier(): BackupProblemNotifier
}
