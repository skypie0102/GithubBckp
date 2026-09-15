package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubAuthManager
import com.skypie0102.githubbckp.github.GithubRepositoryRestoreGateway
import com.skypie0102.githubbckp.github.GithubRestoreRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

data class GithubRestorePublishResult(
    val repositoryFullName: String,
    val repositoryUrl: String,
    val pushedRefCount: Int,
    val restoredLfsObjectCount: Int,
    val skippedReadOnlyRefs: List<String>,
)

@Singleton
class GithubMirrorRestorePublisher @Inject constructor(
    private val restoreCoordinator: MirrorRestoreCoordinator,
    private val repositoryGateway: GithubRepositoryRestoreGateway,
    private val authManager: GithubAuthManager,
    private val lfsPointerScanner: GitLfsPointerScanner,
    private val lfsUploadService: GitLfsUploadService,
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
            failurePrefix = "${created.fullName} was created, but recovery failed",
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

        return try {
            withContext(Dispatchers.IO) {
                val lfsPointers = lfsPointerScanner.scan(repositoryDirectory)
                val lfsObjectCount = lfsUploadService.uploadAll(
                    repositoryFullName = repository.fullName,
                    accessToken = token,
                    pointers = lfsPointers,
                    repositoryDirectory = repositoryDirectory,
                )
                val push = pushService.push(
                    repositoryDirectory = repositoryDirectory,
                    remoteUri = repository.cloneUrl,
                    credentialsProvider = UsernamePasswordCredentialsProvider("x-access-token", token),
                )
                GithubRestorePublishResult(
                    repositoryFullName = repository.fullName,
                    repositoryUrl = repository.htmlUrl,
                    pushedRefCount = push.pushedRefCount,
                    restoredLfsObjectCount = lfsObjectCount,
                    skippedReadOnlyRefs = push.skippedReadOnlyRefs,
                )
            }
        } catch (throwable: Throwable) {
            throw IOException(
                "$failurePrefix: ${throwable.message ?: throwable.javaClass.simpleName}",
                throwable,
            )
        }
    }
}
