package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import com.skypie0102.githubbckp.worker.BackupCadence

enum class RepositoryBackupHealthState {
    PROTECTED,
    WARNING,
    FAILED,
    STALE,
    NEVER_BACKED_UP,
}

data class RepositoryBackupHealth(
    val repositoryId: Long,
    val owner: String,
    val name: String,
    val state: RepositoryBackupHealthState,
    val latestVerifiedAtEpochMs: Long? = null,
    val latestAttemptAtEpochMs: Long? = null,
    val latestAttemptStatus: BackupStatus? = null,
    val warningMessage: String? = null,
    val errorMessage: String? = null,
) {
    val fullName: String = "$owner/$name"
}

data class BackupHealthSummary(
    val repositories: List<RepositoryBackupHealth> = emptyList(),
) {
    val selectedCount: Int = repositories.size
    val protectedCount: Int = repositories.count {
        it.state == RepositoryBackupHealthState.PROTECTED ||
            it.state == RepositoryBackupHealthState.WARNING
    }
    val attentionCount: Int = repositories.count {
        it.state != RepositoryBackupHealthState.PROTECTED
    }
    val problemRepositories: List<RepositoryBackupHealth> = repositories.filter {
        it.state != RepositoryBackupHealthState.PROTECTED
    }
}

fun summarizeBackupHealth(
    repositories: List<RepositoryEntity>,
    backups: List<BackupEntity>,
    scheduleEnabled: Boolean,
    cadence: BackupCadence,
    nowEpochMs: Long,
): BackupHealthSummary {
    val selectedRepositories = repositories
        .filter { it.isAvailable && it.selectedForBackup }
        .sortedWith(compareBy<RepositoryEntity> { it.owner.lowercase() }.thenBy { it.name.lowercase() })
    if (selectedRepositories.isEmpty()) return BackupHealthSummary()

    val backupsByRepository = backups.groupBy { it.repositoryId }
    val freshnessWindowMs = cadence.repeatHours * 2L * MILLIS_PER_HOUR

    val health = selectedRepositories.map { repository ->
        val repositoryBackups = backupsByRepository[repository.githubId].orEmpty()
        val latestAttempt = repositoryBackups.maxWithOrNull(
            compareBy<BackupEntity> { it.startedAtEpochMs }.thenBy { it.id },
        )
        val latestVerified = repositoryBackups
            .asSequence()
            .filter { it.status == BackupStatus.COMPLETED && it.remoteDeletedAtEpochMs == null }
            .maxWithOrNull(
                compareBy<BackupEntity> { it.completedAtEpochMs ?: it.startedAtEpochMs }.thenBy { it.id },
            )

        val verifiedAt = latestVerified?.completedAtEpochMs ?: latestVerified?.startedAtEpochMs
        val attemptAfterVerified = latestAttempt != null && latestVerified != null &&
            latestAttempt.id != latestVerified.id &&
            latestAttempt.startedAtEpochMs >= (verifiedAt ?: Long.MIN_VALUE)

        val state = when {
            latestVerified == null -> RepositoryBackupHealthState.NEVER_BACKED_UP
            attemptAfterVerified && latestAttempt?.status == BackupStatus.FAILED -> RepositoryBackupHealthState.FAILED
            scheduleEnabled && verifiedAt != null && nowEpochMs - verifiedAt > freshnessWindowMs ->
                RepositoryBackupHealthState.STALE
            attemptAfterVerified && latestAttempt?.status == BackupStatus.CANCELLED -> RepositoryBackupHealthState.WARNING
            !latestVerified.warningMessage.isNullOrBlank() -> RepositoryBackupHealthState.WARNING
            else -> RepositoryBackupHealthState.PROTECTED
        }

        RepositoryBackupHealth(
            repositoryId = repository.githubId,
            owner = repository.owner,
            name = repository.name,
            state = state,
            latestVerifiedAtEpochMs = verifiedAt,
            latestAttemptAtEpochMs = latestAttempt?.startedAtEpochMs,
            latestAttemptStatus = latestAttempt?.status,
            warningMessage = latestVerified?.warningMessage,
            errorMessage = if (attemptAfterVerified) latestAttempt?.errorMessage else null,
        )
    }

    return BackupHealthSummary(repositories = health)
}

private const val MILLIS_PER_HOUR = 60L * 60L * 1000L
