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
    return BackupAuditSnapshot(
        backupId = id,
        repositoryId = repositoryId,
        repositoryFullName = repository?.let { "${it.owner}/${it.name}" },
        repositoryDefaultBranch = repository?.defaultBranch,
        repositoryPrivate = repository?.isPrivate,
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

fun BackupAuditSnapshot.toBackupAuditJson(
    generatedAtEpochMs: Long = System.currentTimeMillis(),
): JSONObject {
    val repository = JSONObject()
        .put("githubId", repositoryId)
        .putNullable("currentKnownFullName", repositoryFullName)
        .putNullable("currentKnownDefaultBranch", repositoryDefaultBranch)
        .putNullable("currentKnownPrivate", repositoryPrivate)
        .put(
            "metadataSource",
            if (repositoryFullName == null) "unavailable" else "current-local-repository-cache",
        )

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
    if (repositoryFullName != null) {
        limitations.put(
            "Repository owner/name/default-branch/privacy values come from the current local repository cache, not an immutable backup-time repository snapshot.",
        )
    }

    return JSONObject()
        .put("formatVersion", 1)
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
