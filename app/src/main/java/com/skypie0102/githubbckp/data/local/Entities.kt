package com.skypie0102.githubbckp.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.skypie0102.githubbckp.backup.MirrorStatus

@Entity(tableName = "repositories")
data class RepositoryEntity(
    @PrimaryKey val githubId: Long,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val selectedForBackup: Boolean = true,
    @ColumnInfo(defaultValue = "1") val isAvailable: Boolean = true,
)

@Entity(tableName = "mirrors")
data class MirrorEntity(
    @PrimaryKey val repositoryId: Long,
    val status: MirrorStatus = MirrorStatus.IDLE,
    val archiveName: String? = null,
    val archiveSizeBytes: Long? = null,
    val lastCommitSha: String? = null,
    val lastStartedAtEpochMs: Long? = null,
    val lastCompletedAtEpochMs: Long? = null,
    val lastSuccessfulAtEpochMs: Long? = null,
    val lastError: String? = null,
)
