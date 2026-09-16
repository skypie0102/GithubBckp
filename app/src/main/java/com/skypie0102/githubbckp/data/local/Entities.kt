package com.skypie0102.githubbckp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
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
)

@Entity(tableName = "backups")
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
)
