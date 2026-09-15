package com.skypie0102.githubbckp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType

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
    val remoteFileId: String? = null,
    val errorMessage: String? = null,
)
