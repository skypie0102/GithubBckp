package com.skypie0102.githubbckp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RepositoryEntity::class, MirrorEntity::class],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repositoryDao(): RepositoryDao
    abstract fun mirrorDao(): MirrorDao
}
