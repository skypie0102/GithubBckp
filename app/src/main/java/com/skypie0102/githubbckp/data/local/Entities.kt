package com.skypie0102.githubbckp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.skypie0102.githubbckp.backup.BackupOrigin
import com.skypie0102.githubbckp.backup.BackupReverificationStatus
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.storage.StorageDestination

@Entity(tableName = "repositories")
data class RepositoryEntity(
    @PrimaryKey val githubId: Long,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val selectedForBackup: Boolean = true,
    val lastKnownSha: String? = null,
    @ColumnInfo(defaultValue = "1") val isAvailable: Boolean = true,
)

/**
 * vNext one-to-one mirror state. repositoryId is both the GitHub repository ID
 * and the primary key, so Room cannot represent two current mirrors for one
 * repository.
 */
@Entity(tableName = "mirrors")
data class MirrorEntity(
    @PrimaryKey val repositoryId: Long,
    val archiveUri: String? = null,
    val archiveSizeBytes: Long? = null,
    val archiveSha256: String? = null,
    @ColumnInfo(defaultValue = "1") val formatVersion: Int = 1,
    val lastCheckedAtEpochMs: Long? = null,
    val lastSuccessfulSyncAtEpochMs: Long? = null,
    val lastChangedAtEpochMs: Long? = null,
    val lastAttemptAtEpochMs: Long? = null,
    val lastAttemptStatus: String? = null,
    val lastSourceHead: String? = null,
    val lastRefsDigest: String? = null,
    val lastError: String? = null,
    val lastWarning: String? = null,
)

@Entity(
    tableName = "backups",
    indices = [Index(value = ["scheduledRunId"])],
)
data class BackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repositoryId: Long,
    val type: BackupType,
    val status: BackupStatus,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val checksumSha256: String? = null,
    val storageProvider: StorageDestination? = null,
    val remoteFileId: String? = null,
    val remoteFileName: String? = null,
    val remoteSizeBytes: Long? = null,
    val remoteChecksumMd5: String? = null,
    val remoteDeletedAtEpochMs: Long? = null,
    val warningMessage: String? = null,
    val errorMessage: String? = null,
    val repositoryOwnerAtBackup: String? = null,
    val repositoryNameAtBackup: String? = null,
    val repositoryDefaultBranchAtBackup: String? = null,
    val repositoryPrivateAtBackup: Boolean? = null,
    val origin: BackupOrigin? = null,
    val scheduledRunId: String? = null,
    val lastReverifiedAtEpochMs: Long? = null,
    val lastReverificationStatus: BackupReverificationStatus? = null,
    val lastReverificationMessage: String? = null,
)
