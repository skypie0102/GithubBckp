package com.skypie0102.githubbckp.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LatestReleaseDao {
    @Upsert
    suspend fun upsert(release: LatestReleaseEntity)

    @Query("SELECT * FROM latest_releases WHERE repositoryId = :repositoryId LIMIT 1")
    suspend fun get(repositoryId: Long): LatestReleaseEntity?

    @Query("SELECT * FROM latest_releases ORDER BY repositoryId")
    fun observeAll(): Flow<List<LatestReleaseEntity>>

    @Query("SELECT * FROM latest_releases ORDER BY repositoryId")
    suspend fun getAll(): List<LatestReleaseEntity>

    @Query("DELETE FROM latest_releases WHERE repositoryId = :repositoryId")
    suspend fun delete(repositoryId: Long)
}
