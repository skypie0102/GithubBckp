package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.mirror.GitMirrorOperations
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

data class GithubRepositoryRemoteState(
    val readable: Boolean,
    val refsDigest: String? = null,
)

@Singleton
class GithubRepositoryAccessVerifier @Inject constructor(
    private val authManager: GithubAuthManager,
) {
    suspend fun inspect(repository: GithubRepository): GithubRepositoryRemoteState =
        withContext(Dispatchers.IO) {
            val token = authManager.requireAccessToken()
            val credentials = UsernamePasswordCredentialsProvider("x-access-token", token)
            runCatching {
                GithubRepositoryRemoteState(
                    readable = true,
                    refsDigest = GitMirrorOperations.remoteRefsDigest(
                        remoteUri = repository.remoteUrl,
                        credentialsProvider = credentials,
                    ),
                )
            }.getOrElse {
                GithubRepositoryRemoteState(readable = false)
            }
        }

    suspend fun canRead(repository: GithubRepository): Boolean = inspect(repository).readable

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
