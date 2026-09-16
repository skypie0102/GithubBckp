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
import com.skypie0102.githubbckp.backup.BackupType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val schedulePreferences: BackupSchedulePreferences,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(
        repositoryIds: List<Long>,
        type: BackupType = BackupType.SOURCE_ARCHIVE,
    ) {
        enqueueWithConstraints(
            repositoryIds = repositoryIds,
            type = type,
            constraints = manualConstraints(),
            originTag = TAG_MANUAL,
        )
    }

    fun enqueueScheduled(
        repositoryIds: List<Long>,
        type: BackupType,
    ) {
        enqueueWithConstraints(
            repositoryIds = repositoryIds,
            type = type,
            constraints = scheduledConstraints(),
            originTag = TAG_SCHEDULED,
        )
    }

    fun scheduleSettings(): BackupScheduleSettings = schedulePreferences.settings()

    fun scheduledRunStatus(): ScheduledBackupRunStatus? = schedulePreferences.runStatus()

    fun observeScheduledRunStatus(): Flow<ScheduledBackupRunStatus?> = schedulePreferences.observeRunStatus()

    fun updateSchedule(settings: BackupScheduleSettings) {
        schedulePreferences.save(settings)
        applySchedule(settings)
    }

    fun reconcileSchedule() {
        applySchedule(schedulePreferences.settings())
    }

    private fun applySchedule(settings: BackupScheduleSettings) {
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
            .addTag(TAG_SCHEDULE_CONTROLLER)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SCHEDULE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun enqueueWithConstraints(
        repositoryIds: List<Long>,
        type: BackupType,
        constraints: Constraints,
        originTag: String,
    ) {
        repositoryIds.forEach { repositoryId ->
            val request = OneTimeWorkRequestBuilder<RepositoryBackupWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        RepositoryBackupWorker.KEY_REPOSITORY_ID to repositoryId,
                        RepositoryBackupWorker.KEY_BACKUP_TYPE to type.name,
                    ),
                )
                .addTag("backup-$repositoryId")
                .addTag(originTag)
                .build()

            workManager.enqueueUniqueWork(
                "backup-$repositoryId-${type.name}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    private fun manualConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    companion object {
        private const val SCHEDULE_WORK_NAME = "scheduled-repository-backups"
        private const val TAG_MANUAL = "backup-origin-manual"
        private const val TAG_SCHEDULED = "backup-origin-scheduled"
        private const val TAG_SCHEDULE_CONTROLLER = "backup-schedule-controller"

        fun scheduledConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}
