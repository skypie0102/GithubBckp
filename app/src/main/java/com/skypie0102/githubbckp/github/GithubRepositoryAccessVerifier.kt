package com.skypie0102.githubbckp.github

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

@Singleton
class GithubRepositoryAccessVerifier @Inject constructor(
    private val authManager: GithubAuthManager,
) {
    suspend fun canRead(repository: GithubRepository): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        runCatching {
            Git.lsRemoteRepository()
                .setRemote(repository.remoteUrl)
                .setCredentialsProvider(
                    UsernamePasswordCredentialsProvider("x-access-token", token),
                )
                .call()
            true
        }.getOrDefault(false)
    }
}
