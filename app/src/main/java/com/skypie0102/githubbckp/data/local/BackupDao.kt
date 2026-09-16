package com.skypie0102.githubbckp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {
    @Upsert
    suspend fun upsertRepositoryRows(repositories: List<RepositoryEntity>)

    @Query("UPDATE repositories SET isAvailable = 0")
    suspend fun markAllRepositoriesUnavailable()

    @Transaction
    suspend fun upsertRepositories(repositories: List<RepositoryEntity>) {
        markAllRepositoriesUnavailable()
        val normalized = normalizeRepositoryInventory(repositories)
        if (normalized.isNotEmpty()) {
            upsertRepositoryRows(normalized)
        }
    }

    @Query("SELECT * FROM repositories WHERE isAvailable = 1 ORDER BY owner, name")
    fun observeRepositories(): Flow<List<RepositoryEntity>>

    @Query("SELECT * FROM repositories ORDER BY owner, name")
    suspend fun getRepositories(): List<RepositoryEntity>

    @Query("SELECT * FROM repositories WHERE isAvailable = 1 ORDER BY owner, name")
    suspend fun getAvailableRepositories(): List<RepositoryEntity>

    @Query("SELECT * FROM repositories WHERE githubId = :githubId AND isAvailable = 1 LIMIT 1")
    suspend fun getRepository(githubId: Long): RepositoryEntity?

    @Query("UPDATE repositories SET selectedForBackup = :selected WHERE githubId = :githubId AND isAvailable = 1")
    suspend fun setRepositorySelected(githubId: Long, selected: Boolean)

    @Query("UPDATE repositories SET selectedForBackup = :selected WHERE isAvailable = 1")
    suspend fun setAvailableRepositoriesSelected(selected: Boolean)

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
            warningMessage = :warningMessage,
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
        warningMessage: String?,
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

    @Query(
        """
        SELECT * FROM backups AS backup
        WHERE backup.id = (
            SELECT attempt.id
            FROM backups AS attempt
            WHERE attempt.repositoryId = backup.repositoryId
            ORDER BY attempt.startedAtEpochMs DESC, attempt.id DESC
            LIMIT 1
        )
        OR backup.id = (
            SELECT verified.id
            FROM backups AS verified
            WHERE verified.repositoryId = backup.repositoryId
              AND verified.status = 'COMPLETED'
              AND verified.remoteDeletedAtEpochMs IS NULL
            ORDER BY verified.completedAtEpochMs DESC, verified.id DESC
            LIMIT 1
        )
        ORDER BY backup.repositoryId, backup.startedAtEpochMs DESC, backup.id DESC
        """,
    )
    fun observeBackupHealthHistory(): Flow<List<BackupEntity>>

    @Query(
        """
        SELECT * FROM backups
        WHERE scheduledRunId = :scheduledRunId
        ORDER BY startedAtEpochMs ASC, id ASC
        """,
    )
    fun observeBackupsForScheduledRun(scheduledRunId: String): Flow<List<BackupEntity>>
}
