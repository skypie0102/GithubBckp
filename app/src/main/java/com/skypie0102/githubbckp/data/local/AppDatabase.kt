package com.skypie0102.githubbckp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RepositoryEntity::class, MirrorEntity::class, LatestReleaseEntity::class],
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repositoryDao(): RepositoryDao
    abstract fun mirrorDao(): MirrorDao
    abstract fun latestReleaseDao(): LatestReleaseDao
}
