package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate

data class MirrorPushResult(
    val pushedRefCount: Int,
    val skippedReadOnlyRefs: List<String>,
)

@Singleton
class GitMirrorPushService @Inject constructor() {
    suspend fun requireRemoteEmpty(
        remoteUri: String,
        credentialsProvider: CredentialsProvider? = null,
    ) = withContext(Dispatchers.IO) {
        requireEmpty(listRemoteRefs(remoteUri, credentialsProvider))
    }

    suspend fun push(
        repositoryDirectory: File,
        remoteUri: String,
        credentialsProvider: CredentialsProvider? = null,
    ): MirrorPushResult = pushInternal(
        repositoryDirectory = repositoryDirectory,
        remoteUri = remoteUri,
        credentialsProvider = credentialsProvider,
        allowExactAlreadyPublished = false,
    )

    /**
     * Resume-only path. A non-empty remote is accepted only when every writable
     * ref name and object ID exactly matches the local mirror and no unexpected
     * advertised refs exist. This closes the crash window after a successful
     * push but before the recovery transaction phase is persisted.
     */
    suspend fun pushOrReconcilePublished(
        repositoryDirectory: File,
        remoteUri: String,
        credentialsProvider: CredentialsProvider? = null,
    ): MirrorPushResult = pushInternal(
        repositoryDirectory = repositoryDirectory,
        remoteUri = remoteUri,
        credentialsProvider = credentialsProvider,
        allowExactAlreadyPublished = true,
    )

    /**
     * Read-only post-publication proof used by the recovery drill. Every writable
     * ref from the local mirror must still be advertised by the target at the
     * exact same object ID, with no unexpected writable refs present.
     */
    suspend fun verifyPublishedRefs(
        repositoryDirectory: File,
        remoteUri: String,
        credentialsProvider: CredentialsProvider? = null,
    ): Int = withContext(Dispatchers.IO) {
        require(repositoryDirectory.isDirectory) { "Restored mirror directory is missing" }
        FileRepositoryBuilder()
            .setGitDir(repositoryDirectory)
            .setBare()
            .build()
            .use { repository ->
                check(repository.isBare) { "Restore source is not a bare Git repository" }
                val expectedRefs = repository.refDatabase.getRefsByPrefix("refs/")
                    .filterNot { isGithubReadOnlyRef(it.name) }
                    .associate { it.name to it.objectId.name }
                val advertised = listRemoteRefs(remoteUri, credentialsProvider)
                if (!remoteExactlyMatches(advertised, expectedRefs)) {
                    throw IOException("Published Git refs do not exactly match the restored mirror")
                }
                expectedRefs.size
            }
    }

    private suspend fun pushInternal(
        repositoryDirectory: File,
        remoteUri: String,
        credentialsProvider: CredentialsProvider?,
        allowExactAlreadyPublished: Boolean,
    ): MirrorPushResult = withContext(Dispatchers.IO) {
        require(repositoryDirectory.isDirectory) { "Restored mirror directory is missing" }
        FileRepositoryBuilder()
            .setGitDir(repositoryDirectory)
            .setBare()
            .build()
            .use { repository ->
                check(repository.isBare) { "Restore source is not a bare Git repository" }
                pushRepository(
                    repository = repository,
                    remoteUri = remoteUri,
                    credentialsProvider = credentialsProvider,
                    allowExactAlreadyPublished = allowExactAlreadyPublished,
                )
            }
    }

    private fun pushRepository(
        repository: Repository,
        remoteUri: String,
        credentialsProvider: CredentialsProvider?,
        allowExactAlreadyPublished: Boolean,
    ): MirrorPushResult {
        val allRefs = repository.refDatabase.getRefsByPrefix("refs/")
        val skipped = allRefs
            .map { it.name }
            .filter(::isGithubReadOnlyRef)
            .sorted()
        val pushRefs = allRefs.filterNot { isGithubReadOnlyRef(it.name) }
        val expectedRefs = pushRefs.associate { it.name to it.objectId.name }

        val advertised = listRemoteRefs(remoteUri, credentialsProvider)
        if (advertised.isNotEmpty()) {
            if (allowExactAlreadyPublished && remoteExactlyMatches(advertised, expectedRefs)) {
                return MirrorPushResult(
                    pushedRefCount = pushRefs.size,
                    skippedReadOnlyRefs = skipped,
                )
            }
            requireEmpty(advertised)
        }

        if (pushRefs.isEmpty()) {
            return MirrorPushResult(pushedRefCount = 0, skippedReadOnlyRefs = skipped)
        }

        val refSpecs = pushRefs.map { ref -> RefSpec("${ref.name}:${ref.name}") }
        val command = Git(repository)
            .push()
            .setRemote(remoteUri)
            .setRefSpecs(refSpecs)
        if (credentialsProvider != null) command.setCredentialsProvider(credentialsProvider)

        val results = command.call().toList()
        val failures = results
            .flatMap { it.remoteUpdates }
            .filterNot { update ->
                update.status == RemoteRefUpdate.Status.OK ||
                    update.status == RemoteRefUpdate.Status.UP_TO_DATE
            }
        if (failures.isNotEmpty()) {
            val detail = failures.take(8).joinToString { update ->
                "${update.remoteName}: ${update.status}${update.message?.let { " ($it)" }.orEmpty()}"
            }
            throw IOException("Mirror push did not restore every writable ref: $detail")
        }

        return MirrorPushResult(
            pushedRefCount = pushRefs.size,
            skippedReadOnlyRefs = skipped,
        )
    }

    private fun requireEmpty(advertised: List<Pair<String, String>>) {
        if (advertised.isEmpty()) return
        val preview = advertised.map { it.first }.distinct().sorted().take(5).joinToString()
        throw IOException(
            "Restore target is not empty; found ${advertised.size} advertised Git ref(s): $preview",
        )
    }

    private fun listRemoteRefs(
        remoteUri: String,
        credentialsProvider: CredentialsProvider?,
    ): List<Pair<String, String>> {
        val command = Git.lsRemoteRepository().setRemote(remoteUri)
        if (credentialsProvider != null) command.setCredentialsProvider(credentialsProvider)
        return command.call().mapNotNull { ref ->
            ref.objectId?.name?.let { ref.name to it }
        }
    }

    private fun remoteExactlyMatches(
        advertised: List<Pair<String, String>>,
        expectedRefs: Map<String, String>,
    ): Boolean {
        val relevant = advertised.filterNot { (name, _) -> name == "HEAD" }
        if (relevant.any { (name, _) -> isGithubReadOnlyRef(name) }) return false
        val remoteRefs = relevant
            .filter { (name, _) -> name.startsWith("refs/") }
            .associate { it }
        if (remoteRefs.size != relevant.size) return false
        return remoteRefs == expectedRefs
    }

    private fun isGithubReadOnlyRef(name: String): Boolean =
        name.startsWith(GITHUB_PULL_REF_PREFIX)

    private companion object {
        const val GITHUB_PULL_REF_PREFIX = "refs/pull/"
    }
}
