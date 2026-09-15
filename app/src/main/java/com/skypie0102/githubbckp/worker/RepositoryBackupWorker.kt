package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.skypie0102.githubbckp.backup.BackupCoordinator
import com.skypie0102.githubbckp.backup.BackupRequest
import com.skypie0102.githubbckp.backup.BackupType
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
        val type = runCatching {
            BackupType.valueOf(inputData.getString(KEY_BACKUP_TYPE) ?: BackupType.SOURCE_ARCHIVE.name)
        }.getOrElse { return Result.failure() }

        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            BackupWorkerDependencies::class.java,
        )
        val repository = dependencies.backupDao().getRepository(repositoryId)
            ?: return Result.failure()
        val success = dependencies.backupCoordinator().run(
            BackupRequest(
                repository = repository.toRepositoryRef(),
                type = type,
            ),
        )
        return if (success) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_REPOSITORY_ID = "repository_id"
        const val KEY_BACKUP_TYPE = "backup_type"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerDependencies {
    fun backupDao(): BackupDao
    fun backupCoordinator(): BackupCoordinator
}
