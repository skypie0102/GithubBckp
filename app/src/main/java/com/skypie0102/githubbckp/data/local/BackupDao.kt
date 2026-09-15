package com.skypie0102.githubbckp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {
    @Upsert
    suspend fun upsertRepositories(repositories: List<RepositoryEntity>)

    @Query("SELECT * FROM repositories ORDER BY owner, name")
    fun observeRepositories(): Flow<List<RepositoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: BackupEntity): Long

    @Query("SELECT * FROM backups ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecentBackups(limit: Int = 50): Flow<List<BackupEntity>>
}
