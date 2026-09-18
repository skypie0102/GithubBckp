package com.skypie0102.githubbckp.data.local

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "github-backup.db")
            .addMigrations(
                DatabaseMigrations.MIGRATION_1_2,
                DatabaseMigrations.MIGRATION_2_3,
                DatabaseMigrations.MIGRATION_3_4,
                DatabaseMigrations.MIGRATION_4_5,
                DatabaseMigrations.MIGRATION_5_6,
                DatabaseMigrations.MIGRATION_6_7,
                DatabaseMigrations.MIGRATION_7_8,
                DatabaseMigrations.MIGRATION_8_9,
                DatabaseMigrations.MIGRATION_9_10,
                DatabaseMigrations.MIGRATION_10_11,
                DatabaseMigrations.MIGRATION_11_12,
            )
            .build()

    @Provides
    fun provideRepositoryDao(database: AppDatabase): RepositoryDao = database.repositoryDao()

    @Provides
    fun provideMirrorDao(database: AppDatabase): MirrorDao = database.mirrorDao()

    @Provides
    fun provideLatestReleaseDao(database: AppDatabase): LatestReleaseDao =
        database.latestReleaseDao()
}
