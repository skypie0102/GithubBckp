package com.skypie0102.githubbckp.github

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

@Singleton
class GithubRepositoryAccessVerifier @Inject constructor(
    private val authManager: GithubAuthManager,
) {
    suspend fun canRead(repository: GithubRepository): Boolean =
        canReadRemote(repository.remoteUrl)

    suspend fun canReadRemote(remoteUrl: String): Boolean = withContext(Dispatchers.IO) {
        val token = authManager.requireAccessToken()
        gitRemoteReadable(
            remoteUrl = remoteUrl,
            credentialsProvider = UsernamePasswordCredentialsProvider("x-access-token", token),
        )
    }
}

internal fun gitRemoteReadable(
    remoteUrl: String,
    credentialsProvider: CredentialsProvider? = null,
): Boolean = runCatching {
    val command = Git.lsRemoteRepository().setRemote(remoteUrl)
    credentialsProvider?.let(command::setCredentialsProvider)
    command.call()
    true
}.getOrDefault(false)
