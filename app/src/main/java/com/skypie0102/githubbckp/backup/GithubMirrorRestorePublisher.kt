package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubRepositoryRestoreGateway
import com.skypie0102.githubbckp.github.GithubRestoreRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

data class GithubRestorePublishResult(
    val repositoryFullName: String,
    val repositoryUrl: String,
    val pushedRefCount: Int,
    val skippedReadOnlyRefs: List<String>,
)

@Singleton
class GithubMirrorRestorePublisher @Inject constructor(
    private val restoreCoordinator: MirrorRestoreCoordinator,
    private val repositoryGateway: GithubRepositoryRestoreGateway,
    private val authManager: GithubAuthManager,
    private val pushService: GitMirrorPushService,
) {
    suspend fun publishToNewRepository(
        restoreId: String,
        repositoryName: String,
        isPrivate: Boolean,
    ): GithubRestorePublishResult {
        val created = repositoryGateway.createRepository(repositoryName, isPrivate)
        return publish(
            restoreId = restoreId,
            repository = created,
            failurePrefix = "${created.fullName} was created, but the mirror push failed",
        )
    }

    suspend fun publishToExistingEmptyRepository(
        restoreId: String,
        repositoryFullName: String,
    ): GithubRestorePublishResult {
        val existing = repositoryGateway.getRepository(repositoryFullName)
        return publish(
            restoreId = restoreId,
            repository = existing,
            failurePrefix = "Restore to ${existing.fullName} failed",
        )
    }

    private suspend fun publish(
        restoreId: String,
        repository: GithubRestoreRepository,
        failurePrefix: String,
    ): GithubRestorePublishResult {
        val repositoryDirectory = restoreCoordinator.requireRepositoryDirectory(restoreId)
        val token = authManager.requireAccessToken()
        val push = try {
            pushService.push(
                repositoryDirectory = repositoryDirectory,
                remoteUri = repository.cloneUrl,
                credentialsProvider = UsernamePasswordCredentialsProvider("x-access-token", token),
            )
        } catch (throwable: Throwable) {
            throw IOException(
                "$failurePrefix: ${throwable.message ?: throwable.javaClass.simpleName}",
                throwable,
            )
        }

        return GithubRestorePublishResult(
            repositoryFullName = repository.fullName,
            repositoryUrl = repository.htmlUrl,
            pushedRefCount = push.pushedRefCount,
            skippedReadOnlyRefs = push.skippedReadOnlyRefs,
        )
    }
}
