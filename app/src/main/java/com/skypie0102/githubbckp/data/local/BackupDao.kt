package com.skypie0102.githubbckp.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {
    @Upsert
    suspend fun upsertRepositoryRows(repositories: List<RepositoryEntity>)

    @Query("UPDATE repositories SET isAvailable = 0")
    suspend fun markAllRepositoriesUnavailable()

    @Transaction
    suspend fun replaceRepositoryInventory(repositories: List<RepositoryEntity>) {
        markAllRepositoriesUnavailable()
        if (repositories.isNotEmpty()) upsertRepositoryRows(repositories)
    }

    @Query("SELECT * FROM repositories WHERE isAvailable = 1 ORDER BY owner, name")
    fun observeRepositories(): Flow<List<RepositoryEntity>>

    @Query("SELECT * FROM repositories WHERE isAvailable = 1 ORDER BY owner, name")
    suspend fun getAvailableRepositories(): List<RepositoryEntity>

    @Query("SELECT * FROM repositories WHERE isAvailable = 1 AND selectedForBackup = 1 ORDER BY owner, name")
    suspend fun getSelectedRepositories(): List<RepositoryEntity>

    @Query("SELECT * FROM repositories WHERE githubId = :githubId AND isAvailable = 1 LIMIT 1")
    suspend fun getRepository(githubId: Long): RepositoryEntity?

    @Query("UPDATE repositories SET selectedForBackup = :selected WHERE githubId = :githubId AND isAvailable = 1")
    suspend fun setRepositorySelected(githubId: Long, selected: Boolean)

    @Query("UPDATE repositories SET selectedForBackup = :selected WHERE isAvailable = 1")
    suspend fun setAllRepositoriesSelected(selected: Boolean)

    @Upsert
    suspend fun upsertMirror(mirror: MirrorEntity)

    @Query("SELECT * FROM mirrors ORDER BY repositoryId")
    fun observeMirrors(): Flow<List<MirrorEntity>>

    @Query("SELECT * FROM mirrors WHERE repositoryId = :repositoryId LIMIT 1")
    suspend fun getMirror(repositoryId: Long): MirrorEntity?
}
