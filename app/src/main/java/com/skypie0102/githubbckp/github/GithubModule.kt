package com.skypie0102.githubbckp.github

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GithubModule {
    @Binds
    @Singleton
    abstract fun bindGithubGateway(implementation: GithubRestGateway): GithubGateway
}
