package com.skypie0102.githubbckp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

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
