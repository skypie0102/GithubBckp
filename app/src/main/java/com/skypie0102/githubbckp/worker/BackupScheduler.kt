package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.skypie0102.githubbckp.backup.BackupType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(
        repositoryIds: List<Long>,
        type: BackupType = BackupType.SOURCE_ARCHIVE,
    ) {
        repositoryIds.forEach { repositoryId ->
            val request = OneTimeWorkRequestBuilder<RepositoryBackupWorker>()
                .setConstraints(defaultConstraints())
                .setInputData(
                    workDataOf(
                        RepositoryBackupWorker.KEY_REPOSITORY_ID to repositoryId,
                        RepositoryBackupWorker.KEY_BACKUP_TYPE to type.name,
                    ),
                )
                .addTag("backup-$repositoryId")
                .build()

            workManager.enqueueUniqueWork(
                "backup-$repositoryId-${type.name}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    private fun defaultConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()
}
