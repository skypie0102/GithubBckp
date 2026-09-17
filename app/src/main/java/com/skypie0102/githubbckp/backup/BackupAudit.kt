package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.data.local.BackupEntity
import com.skypie0102.githubbckp.data.local.RepositoryEntity
import org.json.JSONArray
import org.json.JSONObject

data class BackupAuditSnapshot(
    val backupId: Long,
    val repositoryId: Long,
    val repositoryFullName: String?,
    val repositoryDefaultBranch: String?,
    val repositoryPrivate: Boolean?,
    val repositoryMetadataSource: String,
    val backupType: String,
    val origin: String?,
    val scheduledRunId: String?,
    val status: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val checksumSha256: String?,
    val storageProvider: String?,
    val remoteFileId: String?,
    val remoteFileName: String?,
    val remoteSizeBytes: Long?,
    val remoteChecksumMd5: String?,
    val remoteDeletedAtEpochMs: Long?,
    val warningMessage: String?,
    val errorMessage: String?,
    val lastReverifiedAtEpochMs: Long?,
    val lastReverificationStatus: String?,
    val lastReverificationMessage: String?,
)

fun BackupEntity.toBackupAuditSnapshot(repository: RepositoryEntity?): BackupAuditSnapshot {
    require(repository == null || repository.githubId == repositoryId) {
        "Repository metadata does not match backup repository ID"
    }
    val hasBackupTimeSnapshot = repositoryOwnerAtBackup != null &&
        repositoryNameAtBackup != null &&
        repositoryDefaultBranchAtBackup != null &&
        repositoryPrivateAtBackup != null
    val metadataSource = when {
        hasBackupTimeSnapshot -> "backup-time-snapshot"
        repository != null -> "current-local-repository-cache"
        else -> "unavailable"
    }
    return BackupAuditSnapshot(
        backupId = id,
        repositoryId = repositoryId,
        repositoryFullName = if (hasBackupTimeSnapshot) {
            "$repositoryOwnerAtBackup/$repositoryNameAtBackup"
        } else {
            repository?.let { "${it.owner}/${it.name}" }
        },
        repositoryDefaultBranch = if (hasBackupTimeSnapshot) {
            repositoryDefaultBranchAtBackup
        } else {
            repository?.defaultBranch
        },
        repositoryPrivate = if (hasBackupTimeSnapshot) {
            repositoryPrivateAtBackup
        } else {
            repository?.isPrivate
        },
        repositoryMetadataSource = metadataSource,
        backupType = type.name,
        origin = origin?.name,
        scheduledRunId = scheduledRunId,
        status = status.name,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        checksumSha256 = checksumSha256,
        storageProvider = storageProvider?.name,
        remoteFileId = remoteFileId,
        remoteFileName = remoteFileName,
        remoteSizeBytes = remoteSizeBytes,
        remoteChecksumMd5 = remoteChecksumMd5,
        remoteDeletedAtEpochMs = remoteDeletedAtEpochMs,
        warningMessage = warningMessage,
        errorMessage = errorMessage,
        lastReverifiedAtEpochMs = lastReverifiedAtEpochMs,
        lastReverificationStatus = lastReverificationStatus?.name,
        lastReverificationMessage = lastReverificationMessage,
    )
}

fun BackupEntity.repositoryDisplayName(repository: RepositoryEntity?): String =
    toBackupAuditSnapshot(repository).repositoryFullName ?: "Repository #$repositoryId"

fun BackupAuditSnapshot.toBackupAuditJson(
    generatedAtEpochMs: Long = System.currentTimeMillis(),
): JSONObject {
    val repository = JSONObject()
        .put("githubId", repositoryId)
        .putNullable("fullName", repositoryFullName)
        .putNullable("defaultBranch", repositoryDefaultBranch)
        .putNullable("private", repositoryPrivate)
        .put("metadataSource", repositoryMetadataSource)

    val storage = JSONObject()
        .putNullable("provider", storageProvider)
        .putNullable("remoteFileId", remoteFileId)
        .putNullable("remoteFileName", remoteFileName)
        .putNullable("remoteSizeBytes", remoteSizeBytes)
        .putNullable("remoteChecksumMd5", remoteChecksumMd5)
        .putNullable("remoteDeletedAtEpochMs", remoteDeletedAtEpochMs)
        .put(
            "remoteState",
            when {
                remoteDeletedAtEpochMs != null -> "REMOTE_OBJECT_REMOVED"
                remoteFileId != null -> "PRESENT_AT_LAST_VERIFICATION"
                else -> "UNKNOWN"
            },
        )

    val creationVerification = JSONObject()
        .putNullable("artifactSha256", checksumSha256)
        .putNullable("providerMd5", remoteChecksumMd5)
        .put(
            "state",
            if (status == BackupStatus.COMPLETED.name && checksumSha256 != null) {
                "PERSISTED_VERIFIED_HISTORY"
            } else {
                "NOT_COMPLETED_OR_NOT_VERIFIED"
            },
        )

    val reverification = JSONObject()
        .putNullable("checkedAtEpochMs", lastReverifiedAtEpochMs)
        .putNullable("status", lastReverificationStatus)
        .putNullable("detail", lastReverificationMessage)
        .put(
            "state",
            if (lastReverifiedAtEpochMs == null || lastReverificationStatus == null) {
                "NOT_RUN"
            } else {
                "RECORDED"
            },
        )

    val backup = JSONObject()
        .put("id", backupId)
        .put("type", backupType)
        .putNullable("origin", origin)
        .putNullable("scheduledRunId", scheduledRunId)
        .put("status", status)
        .put("startedAtEpochMs", startedAtEpochMs)
        .putNullable("completedAtEpochMs", completedAtEpochMs)
        .putNullable("completenessWarning", warningMessage)
        .putNullable("errorMessage", errorMessage)

    val limitations = JSONArray()
        .put(
            "Exporting this report does not itself access the remote artifact; it reports the original verification plus the latest separately recorded on-demand re-verification result, if one exists.",
        )
    when (repositoryMetadataSource) {
        "current-local-repository-cache" -> limitations.put(
            "This pre-v4 backup has no immutable repository metadata snapshot; owner/name/default-branch/privacy values come from the current local repository cache.",
        )
        "unavailable" -> limitations.put(
            "This pre-v4 backup has no immutable repository metadata snapshot and current repository display metadata is unavailable.",
        )
    }
    if (backupType != BackupType.GIT_MIRROR.name) {
        limitations.put(
            "This row describes a legacy backup type. Current GithubBckp versions create Git mirrors only.",
        )
    }
    if (origin == null) {
        limitations.put(
            "This backup predates origin tracking or was queued before origin metadata was available; manual versus scheduled origin is unknown.",
        )
    } else if (origin == BackupOrigin.SCHEDULED.name && scheduledRunId == null) {
        limitations.put(
            "This scheduled backup predates scheduled-run correlation or was queued before a run ID was available.",
        )
    }
    if (origin == BackupOrigin.SCHEDULED.name) {
        limitations.put(
            "Scheduled controller fan-out uses WorkManager unique-work KEEP semantics; a controller run may request a repository while older work is still active, in which case no new child backup row is created for that newer run.",
        )
    }

    return JSONObject()
        .put("formatVersion", 6)
        .put("reportType", "github-backup-artifact-audit")
        .put("generatedAtEpochMs", generatedAtEpochMs)
        .put("repository", repository)
        .put("backup", backup)
        .put("storage", storage)
        .put("integrityVerification", creationVerification)
        .put("latestReverification", reverification)
        .put("limitations", limitations)
}

fun BackupAuditSnapshot.backupAuditReportFileName(): String {
    val repositoryPart = repositoryFullName
        ?.replace('/', '-')
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.take(80)
        ?.ifBlank { null }
        ?: "repository-$repositoryId"
    return "$repositoryPart-backup-$backupId-audit.json"
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)
