package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubGateway
import com.skypie0102.githubbckp.github.GithubRestGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    @Singleton
    abstract fun bindGithubGateway(implementation: GithubRestGateway): GithubGateway

    @Binds
    @Singleton
    abstract fun bindBackupEngine(implementation: SourceArchiveBackupEngine): BackupEngine
}
