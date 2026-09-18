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
import kotlinx.coroutines.flow.Flow

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val schedulePreferences: BackupSchedulePreferences,
    private val backupProblemNotifier: BackupProblemNotifier,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(repositoryIds: List<Long>) {
        enqueueWithConstraints(
            repositoryIds = repositoryIds,
            origin = BackupOrigin.MANUAL,
            scheduledRunId = null,
            constraints = manualConstraints(),
            originTag = TAG_MANUAL,
        )
    }

    fun enqueueScheduled(
        repositoryIds: List<Long>,
        scheduledRunId: String,
    ) {
        enqueueWithConstraints(
            repositoryIds = repositoryIds,
            origin = BackupOrigin.SCHEDULED,
            scheduledRunId = scheduledRunId,
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
        schedulePreferences.ensureEnabledAt()
        applySchedule(schedulePreferences.settings())
    }

    private fun applySchedule(settings: BackupScheduleSettings) {
        if (!settings.enabled) {
            workManager.cancelUniqueWork(SCHEDULE_WORK_NAME)
            workManager.cancelUniqueWork(HEALTH_CHECK_WORK_NAME)
            backupProblemNotifier.clearOverdueBackupState()
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

        val healthCheckRequest = PeriodicWorkRequestBuilder<BackupHealthCheckWorker>(
            HEALTH_CHECK_REPEAT_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(HEALTH_CHECK_INITIAL_DELAY_HOURS, TimeUnit.HOURS)
            .addTag(TAG_HEALTH_CHECK)
            .build()

        workManager.enqueueUniquePeriodicWork(
            HEALTH_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            healthCheckRequest,
        )
    }

    private fun enqueueWithConstraints(
        repositoryIds: List<Long>,
        origin: BackupOrigin,
        scheduledRunId: String?,
        constraints: Constraints,
        originTag: String,
    ) {
        repositoryIds.forEach { repositoryId ->
            val request = OneTimeWorkRequestBuilder<RepositoryBackupWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        RepositoryBackupWorker.KEY_REPOSITORY_ID to repositoryId,
                        RepositoryBackupWorker.KEY_BACKUP_ORIGIN to origin.name,
                        RepositoryBackupWorker.KEY_SCHEDULED_RUN_ID to scheduledRunId,
                    ),
                )
                .addTag("backup-$repositoryId")
                .addTag(originTag)
                .build()

            workManager.enqueueUniqueWork(
                "backup-$repositoryId-mirror",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    private fun manualConstraints(): Constraints = Constraints.Builder()
        // Manual backups are explicit user actions. Requiring "battery not low"
        // or "storage not low" lets WorkManager stop a backup mid-clone when
        // Android crosses those thresholds. Keep only the network prerequisite;
        // actual storage exhaustion will surface as a concrete backup error.
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object {
        private const val SCHEDULE_WORK_NAME = "scheduled-repository-backups"
        private const val HEALTH_CHECK_WORK_NAME = "backup-health-notification-check"
        private const val TAG_MANUAL = "backup-origin-manual"
        private const val TAG_SCHEDULED = "backup-origin-scheduled"
        private const val TAG_SCHEDULE_CONTROLLER = "backup-schedule-controller"
        private const val TAG_HEALTH_CHECK = "backup-health-check"
        private const val HEALTH_CHECK_REPEAT_HOURS = 24L
        private const val HEALTH_CHECK_INITIAL_DELAY_HOURS = 1L

        fun scheduledConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}
