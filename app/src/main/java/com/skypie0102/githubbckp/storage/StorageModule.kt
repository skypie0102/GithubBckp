package com.skypie0102.githubbckp.storage

import com.skypie0102.githubbckp.storage.drive.GoogleDriveStorageProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {
    @Binds
    @Singleton
    abstract fun bindStorageProvider(implementation: GoogleDriveStorageProvider): StorageProvider
}
