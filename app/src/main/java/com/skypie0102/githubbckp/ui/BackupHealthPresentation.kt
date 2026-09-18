package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.backup.MirrorStatus
import com.skypie0102.githubbckp.data.local.MirrorEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.worker.BackupCadence
import java.util.concurrent.TimeUnit

enum class RepositoryHealth {
    HEALTHY,
    NEEDS_FIRST_BACKUP,
    STALE,
    FAILED,
    RUNNING,
}

data class BackupHealthSummary(
    val selectedCount: Int = 0,
    val healthyCount: Int = 0,
    val needsFirstBackupCount: Int = 0,
    val staleCount: Int = 0,
    val failedCount: Int = 0,
    val runningCount: Int = 0,
)

fun repositoryHealth(
    repository: RepositoryEntity,
    mirror: MirrorEntity?,
    scheduleEnabled: Boolean,
    cadence: BackupCadence,
    nowEpochMs: Long = System.currentTimeMillis(),
): RepositoryHealth {
    if (mirror?.status == MirrorStatus.RUNNING || mirror?.status == MirrorStatus.QUEUED) {
        return RepositoryHealth.RUNNING
    }

    val lastSuccess = mirror?.lastSuccessfulAtEpochMs
        ?: return if (mirror?.status == MirrorStatus.FAILED) {
            RepositoryHealth.FAILED
        } else {
            RepositoryHealth.NEEDS_FIRST_BACKUP
        }

    if (
        mirror.status == MirrorStatus.FAILED &&
        (mirror.lastStartedAtEpochMs ?: 0L) > lastSuccess
    ) {
        return RepositoryHealth.FAILED
    }

    if (scheduleEnabled) {
        val staleAfterMs = TimeUnit.HOURS.toMillis(cadence.repeatHours * 2)
        if (nowEpochMs - lastSuccess > staleAfterMs) {
            return RepositoryHealth.STALE
        }
    }

    return RepositoryHealth.HEALTHY
}

fun summarizeBackupHealth(
    repositories: List<RepositoryEntity>,
    mirrors: List<MirrorEntity>,
    scheduleEnabled: Boolean,
    cadence: BackupCadence,
    nowEpochMs: Long = System.currentTimeMillis(),
): BackupHealthSummary {
    val mirrorByRepository = mirrors.associateBy { it.repositoryId }
    val selected = repositories.filter { it.selectedForBackup && it.isAvailable }
    var healthy = 0
    var first = 0
    var stale = 0
    var failed = 0
    var running = 0

    selected.forEach { repository ->
        when (
            repositoryHealth(
                repository = repository,
                mirror = mirrorByRepository[repository.githubId],
                scheduleEnabled = scheduleEnabled,
                cadence = cadence,
                nowEpochMs = nowEpochMs,
            )
        ) {
            RepositoryHealth.HEALTHY -> healthy++
            RepositoryHealth.NEEDS_FIRST_BACKUP -> first++
            RepositoryHealth.STALE -> stale++
            RepositoryHealth.FAILED -> failed++
            RepositoryHealth.RUNNING -> running++
        }
    }

    return BackupHealthSummary(
        selectedCount = selected.size,
        healthyCount = healthy,
        needsFirstBackupCount = first,
        staleCount = stale,
        failedCount = failed,
        runningCount = running,
    )
}
