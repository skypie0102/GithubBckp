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
                remoteDeletedAtEpochMs != null -> "DELETED_BY_RETENTION"
                remoteFileId != null -> "PRESENT_AT_LAST_VERIFICATION"
                else -> "UNKNOWN"
            },
        )

    val verification = JSONObject()
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

    val backup = JSONObject()
        .put("id", backupId)
        .put("type", backupType)
        .put("status", status)
        .put("startedAtEpochMs", startedAtEpochMs)
        .putNullable("completedAtEpochMs", completedAtEpochMs)
        .putNullable("completenessWarning", warningMessage)
        .putNullable("errorMessage", errorMessage)

    val limitations = JSONArray()
        .put(
            "This report is generated from persisted app history; exporting it does not re-download or re-verify the remote backup artifact.",
        )
    when (repositoryMetadataSource) {
        "current-local-repository-cache" -> limitations.put(
            "This pre-v4 backup has no immutable repository metadata snapshot; owner/name/default-branch/privacy values come from the current local repository cache.",
        )
        "unavailable" -> limitations.put(
            "This pre-v4 backup has no immutable repository metadata snapshot and current repository display metadata is unavailable.",
        )
    }

    return JSONObject()
        .put("formatVersion", 2)
        .put("reportType", "github-backup-artifact-audit")
        .put("generatedAtEpochMs", generatedAtEpochMs)
        .put("repository", repository)
        .put("backup", backup)
        .put("storage", storage)
        .put("integrityVerification", verification)
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
