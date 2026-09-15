package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.backup.RepositoryRef

interface GithubGateway {
    suspend fun listRepositories(): List<RepositoryRef>

    /**
     * Returns a short-lived archive URL for the requested ref.
     * The production adapter should follow GitHub redirects immediately.
     */
    suspend fun archiveUrl(repository: RepositoryRef, ref: String): String
}
