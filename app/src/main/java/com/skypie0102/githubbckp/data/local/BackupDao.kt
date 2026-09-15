package com.skypie0102.githubbckp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {
    @Upsert
    suspend fun upsertRepositories(repositories: List<RepositoryEntity>)

    @Query("SELECT * FROM repositories ORDER BY owner, name")
    fun observeRepositories(): Flow<List<RepositoryEntity>>

    @Query("SELECT * FROM repositories ORDER BY owner, name")
    suspend fun getRepositories(): List<RepositoryEntity>

    @Query("SELECT * FROM repositories WHERE githubId = :githubId LIMIT 1")
    suspend fun getRepository(githubId: Long): RepositoryEntity?

    @Query("UPDATE repositories SET selectedForBackup = :selected WHERE githubId = :githubId")
    suspend fun setRepositorySelected(githubId: Long, selected: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: BackupEntity): Long

    @Query("UPDATE backups SET status = :status WHERE id = :backupId")
    suspend fun updateBackupStatus(backupId: Long, status: BackupStatus)

    @Query(
        """
        UPDATE backups
        SET status = :status,
            completedAtEpochMs = :completedAtEpochMs,
            checksumSha256 = :checksumSha256,
            storageProvider = :storageProvider,
            remoteFileId = :remoteFileId,
            remoteFileName = :remoteFileName,
            remoteSizeBytes = :remoteSizeBytes,
            remoteChecksumMd5 = :remoteChecksumMd5,
            errorMessage = NULL
        WHERE id = :backupId
        """,
    )
    suspend fun completeBackup(
        backupId: Long,
        status: BackupStatus,
        completedAtEpochMs: Long,
        checksumSha256: String,
        storageProvider: String,
        remoteFileId: String,
        remoteFileName: String,
        remoteSizeBytes: Long,
        remoteChecksumMd5: String,
    )

    @Query(
        """
        UPDATE backups
        SET status = :status,
            completedAtEpochMs = :completedAtEpochMs,
            errorMessage = :errorMessage
        WHERE id = :backupId
        """,
    )
    suspend fun failBackup(
        backupId: Long,
        status: BackupStatus,
        completedAtEpochMs: Long,
        errorMessage: String,
    )

    @Query(
        """
        SELECT * FROM backups
        WHERE repositoryId = :repositoryId
          AND type = :type
          AND status = 'COMPLETED'
          AND storageProvider IS NOT NULL
          AND remoteFileId IS NOT NULL
          AND remoteFileName IS NOT NULL
          AND remoteSizeBytes IS NOT NULL
          AND remoteChecksumMd5 IS NOT NULL
          AND remoteDeletedAtEpochMs IS NULL
        ORDER BY completedAtEpochMs DESC, id DESC
        """,
    )
    suspend fun getRetainableBackups(
        repositoryId: Long,
        type: BackupType,
    ): List<BackupEntity>

    @Query("UPDATE backups SET remoteDeletedAtEpochMs = :deletedAtEpochMs WHERE id = :backupId")
    suspend fun markRemoteDeleted(backupId: Long, deletedAtEpochMs: Long)

    @Query("SELECT * FROM backups ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecentBackups(limit: Int = 50): Flow<List<BackupEntity>>
}
