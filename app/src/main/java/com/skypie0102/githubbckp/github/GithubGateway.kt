package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.backup.RepositoryRef

interface GithubGateway {
    suspend fun listRepositories(): List<RepositoryRef>
}
