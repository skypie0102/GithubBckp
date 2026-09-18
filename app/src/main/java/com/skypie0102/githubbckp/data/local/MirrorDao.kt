package com.skypie0102.githubbckp.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MirrorDao {
    @Upsert
    suspend fun upsert(mirror: MirrorEntity)

    @Query("SELECT * FROM mirrors WHERE repositoryId = :repositoryId LIMIT 1")
    suspend fun get(repositoryId: Long): MirrorEntity?

    @Query("SELECT * FROM mirrors ORDER BY repositoryId")
    fun observeAll(): Flow<List<MirrorEntity>>

    @Query("DELETE FROM mirrors WHERE repositoryId = :repositoryId")
    suspend fun delete(repositoryId: Long)
}
