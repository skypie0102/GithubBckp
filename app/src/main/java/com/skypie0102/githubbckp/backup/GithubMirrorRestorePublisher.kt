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
    val restoredReleaseCount: Int,
    val restoredReleaseAssetCount: Int,
    val skippedReadOnlyRefs: List<String>,
)

@Singleton
class GithubMirrorRestorePublisher @Inject constructor(
    private val restoreCoordinator: MirrorRestoreCoordinator,
    private val repositoryGateway: GithubRepositoryRestoreGateway,
    private val authManager: GithubAuthManager,
    private val lfsPointerScanner: GitLfsPointerScanner,
    private val lfsUploadService: GitLfsUploadService,
    private val releaseRestoreService: GithubReleaseRestoreService,
    private val pushService: GitMirrorPushService,
    private val transactionStore: RecoveryTransactionStore,
) {
    suspend fun publishToNewRepository(
        restoreId: String,
        repositoryName: String,
        isPrivate: Boolean,
    ): GithubRestorePublishResult {
        // Verify the recovery-specific OAuth permission before repository
        // creation so an older token cannot leave an unused target behind.
        val recoveryToken = authManager.requireRecoveryAccessToken()
        val existingTransaction = transactionStore.get(restoreId)
        val repository = if (existingTransaction == null) {
            repositoryGateway.createRepository(repositoryName, isPrivate)
        } else {
            if (existingTransaction.targetKind != RecoveryTargetKind.NEW_REPOSITORY) {
                throw IOException(
                    "This restore is already bound to existing target ${existingTransaction.repositoryFullName}",
                )
            }
            resolveBoundRepository(existingTransaction)
        }
        val transaction = transactionStore.bind(
            restoreId = restoreId,
            targetKind = RecoveryTargetKind.NEW_REPOSITORY,
            repository = repository,
        )
        return publish(
            restoreId = restoreId,
            repository = repository,
            initialTransaction = transaction,
            isResume = existingTransaction != null,
            token = recoveryToken,
            failurePrefix = "${repository.fullName} was created or resumed, but recovery failed",
        )
    }

    suspend fun publishToExistingEmptyRepository(
        restoreId: String,
        repositoryFullName: String,
    ): GithubRestorePublishResult {
        val recoveryToken = authManager.requireRecoveryAccessToken()
        val existingTransaction = transactionStore.get(restoreId)
        val repository = if (existingTransaction == null) {
            repositoryGateway.getRepository(repositoryFullName)
        } else {
            if (existingTransaction.targetKind != RecoveryTargetKind.EXISTING_EMPTY_REPOSITORY) {
                throw IOException(
                    "This restore is already bound to new target ${existingTransaction.repositoryFullName}",
                )
            }
            if (!existingTransaction.repositoryFullName.equals(repositoryFullName.trim(), ignoreCase = true)) {
                throw IOException(
                    "This restore is already bound to ${existingTransaction.repositoryFullName}",
                )
            }
            resolveBoundRepository(existingTransaction)
        }
        val transaction = transactionStore.bind(
            restoreId = restoreId,
            targetKind = RecoveryTargetKind.EXISTING_EMPTY_REPOSITORY,
            repository = repository,
        )
        return publish(
            restoreId = restoreId,
            repository = repository,
            initialTransaction = transaction,
            isResume = existingTransaction != null,
            token = recoveryToken,
            failurePrefix = "Restore to ${repository.fullName} failed",
        )
    }

    private suspend fun resolveBoundRepository(transaction: RecoveryTransaction): GithubRestoreRepository {
        val repository = repositoryGateway.getRepository(transaction.repositoryFullName)
        if (repository.id != transaction.repositoryId) {
            throw IOException(
                "Recovery target identity changed; expected GitHub repository ID ${transaction.repositoryId}",
            )
        }
        return repository
    }

    private suspend fun publish(
        restoreId: String,
        repository: GithubRestoreRepository,
        initialTransaction: RecoveryTransaction,
        isResume: Boolean,
        token: String,
        failurePrefix: String,
    ): GithubRestorePublishResult {
        val repositoryDirectory = restoreCoordinator.requireRepositoryDirectory(restoreId)
        val credentials = UsernamePasswordCredentialsProvider("x-access-token", token)

        return try {
            withContext(Dispatchers.IO) {
                var transaction = initialTransaction
                if (transaction.phase == RecoveryPhase.TARGET_BOUND) {
                    // A first-time recovery target must be empty across both the
                    // Git surface and release surface before any LFS bytes move.
                    pushService.requireRemoteEmpty(
                        remoteUri = repository.cloneUrl,
                        credentialsProvider = credentials,
                    )
                    releaseRestoreService.requireNoExistingReleases(
                        repositoryFullName = repository.fullName,
                        accessToken = token,
                    )
                    val lfsPointers = lfsPointerScanner.scan(repositoryDirectory)
                    val lfsObjectCount = lfsUploadService.uploadAll(
                        repositoryFullName = repository.fullName,
                        accessToken = token,
                        pointers = lfsPointers,
                        repositoryDirectory = repositoryDirectory,
                    )
                    transaction = transactionStore.markLfsPublished(
                        restoreId = restoreId,
                        repositoryId = repository.id,
                        lfsObjectCount = lfsObjectCount,
                    )
                }

                if (transaction.phase == RecoveryPhase.LFS_PUBLISHED) {
                    val push = if (isResume) {
                        pushService.pushOrReconcilePublished(
                            repositoryDirectory = repositoryDirectory,
                            remoteUri = repository.cloneUrl,
                            credentialsProvider = credentials,
                        )
                    } else {
                        pushService.push(
                            repositoryDirectory = repositoryDirectory,
                            remoteUri = repository.cloneUrl,
                            credentialsProvider = credentials,
                        )
                    }
                    transaction = transactionStore.markGitPublished(
                        restoreId = restoreId,
                        repositoryId = repository.id,
                        result = push,
                    )
                }

                if (transaction.phase == RecoveryPhase.GIT_PUBLISHED) {
                    val releaseResult = releaseRestoreService.publishBundledReleases(
                        repositoryFullName = repository.fullName,
                        accessToken = token,
                        repositoryDirectory = repositoryDirectory,
                    )
                    transaction = transactionStore.markReleasesPublished(
                        restoreId = restoreId,
                        repositoryId = repository.id,
                        result = releaseResult,
                    )
                }

                check(transaction.phase == RecoveryPhase.RELEASES_PUBLISHED) {
                    "Recovery transaction did not reach release publication"
                }
                GithubRestorePublishResult(
                    repositoryFullName = repository.fullName,
                    repositoryUrl = repository.htmlUrl,
                    pushedRefCount = transaction.pushedRefCount,
                    restoredLfsObjectCount = transaction.lfsObjectCount,
                    restoredReleaseCount = transaction.releaseCount,
                    restoredReleaseAssetCount = transaction.releaseAssetCount,
                    skippedReadOnlyRefs = transaction.skippedReadOnlyRefs,
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
