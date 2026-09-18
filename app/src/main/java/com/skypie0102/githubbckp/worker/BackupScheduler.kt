package com.skypie0102.githubbckp.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.skypie0102.githubbckp.backup.BackupOrigin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: BackupSchedulePreferences,
) {
    private val workManager = WorkManager.getInstance(context)

    fun settings(): BackupScheduleSettings = preferences.settings()

    fun enqueueManual(repositoryIds: List<Long>) {
        repositoryIds.forEach { enqueueRepository(it, BackupOrigin.MANUAL) }
    }

    fun enqueueScheduled(repositoryIds: List<Long>) {
        repositoryIds.forEach { enqueueRepository(it, BackupOrigin.SCHEDULED) }
    }

    fun updateSchedule(settings: BackupScheduleSettings) {
        preferences.save(settings)
        apply(settings)
    }

    fun reconcileSchedule() {
        apply(preferences.settings())
    }

    private fun apply(settings: BackupScheduleSettings) {
        if (!settings.enabled) {
            workManager.cancelUniqueWork(SCHEDULE_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
            settings.cadence.repeatHours,
            TimeUnit.HOURS,
        )
            .setInitialDelay(settings.cadence.repeatHours, TimeUnit.HOURS)
            .setConstraints(scheduledConstraints())
            .build()

        workManager.enqueueUniquePeriodicWork(
            SCHEDULE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun enqueueRepository(repositoryId: Long, origin: BackupOrigin) {
        val request = OneTimeWorkRequestBuilder<RepositoryBackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    RepositoryBackupWorker.KEY_REPOSITORY_ID to repositoryId,
                    RepositoryBackupWorker.KEY_BACKUP_ORIGIN to origin.name,
                ),
            )
            .addTag("mirror-backup")
            .addTag("mirror-backup-$repositoryId")
            .build()

        workManager.enqueueUniqueWork(
            "mirror-backup-$repositoryId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduledConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

    private companion object {
        const val SCHEDULE_WORK_NAME = "scheduled-local-mirror-backups"
    }
}
