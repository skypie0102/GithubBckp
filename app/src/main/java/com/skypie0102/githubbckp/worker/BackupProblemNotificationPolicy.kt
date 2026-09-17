package com.skypie0102.githubbckp.worker

import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity

data class OverdueBackupRepository(
    val repositoryId: Long,
    val owner: String,
    val name: String,
) {
    val fullName: String = "$owner/$name"
}

fun findOverdueBackupRepositories(
    repositories: List<RepositoryEntity>,
    backups: List<BackupEntity>,
    settings: BackupScheduleSettings,
    scheduleEnabledAtEpochMs: Long?,
    nowEpochMs: Long,
): List<OverdueBackupRepository> {
    if (!settings.enabled) return emptyList()

    val freshnessWindowMs = settings.cadence.repeatHours * 2L * MILLIS_PER_HOUR
    val backupsByRepository = backups.groupBy { it.repositoryId }

    return repositories
        .asSequence()
        .filter { it.isAvailable && it.selectedForBackup }
        .filter { repository ->
            val latestVerifiedAt = backupsByRepository[repository.githubId]
                .orEmpty()
                .asSequence()
                .filter { backup ->
                    backup.type == BackupType.GIT_MIRROR &&
                        backup.status == BackupStatus.COMPLETED &&
                        backup.remoteDeletedAtEpochMs == null
                }
                .map { backup -> backup.completedAtEpochMs ?: backup.startedAtEpochMs }
                .maxOrNull()

            val baseline = latestVerifiedAt ?: scheduleEnabledAtEpochMs
            baseline != null && nowEpochMs - baseline > freshnessWindowMs
        }
        .sortedWith(compareBy<RepositoryEntity> { it.owner.lowercase() }.thenBy { it.name.lowercase() })
        .map { repository ->
            OverdueBackupRepository(
                repositoryId = repository.githubId,
                owner = repository.owner,
                name = repository.name,
            )
        }
        .toList()
}

fun shouldNotifyBackupFailure(
    lastNotifiedAtEpochMs: Long?,
    nowEpochMs: Long,
    minimumIntervalMs: Long = FAILURE_NOTIFICATION_INTERVAL_MS,
): Boolean {
    if (lastNotifiedAtEpochMs == null || lastNotifiedAtEpochMs <= 0L) return true
    return nowEpochMs - lastNotifiedAtEpochMs >= minimumIntervalMs
}

const val FAILURE_NOTIFICATION_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L
private const val MILLIS_PER_HOUR = 60L * 60L * 1000L
