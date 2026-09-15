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
    suspend fun push(
        repositoryDirectory: File,
        remoteUri: String,
        credentialsProvider: CredentialsProvider? = null,
    ): MirrorPushResult = withContext(Dispatchers.IO) {
        require(repositoryDirectory.isDirectory) { "Restored mirror directory is missing" }
        FileRepositoryBuilder()
            .setGitDir(repositoryDirectory)
            .setBare()
            .build()
            .use { repository ->
                check(repository.isBare) { "Restore source is not a bare Git repository" }
                pushRepository(repository, remoteUri, credentialsProvider)
            }
    }

    private fun pushRepository(
        repository: Repository,
        remoteUri: String,
        credentialsProvider: CredentialsProvider?,
    ): MirrorPushResult {
        val allRefs = repository.refDatabase.getRefsByPrefix("refs/")
        val skipped = allRefs
            .map { it.name }
            .filter(::isGithubReadOnlyRef)
            .sorted()
        val pushRefs = allRefs.filterNot { isGithubReadOnlyRef(it.name) }
        if (pushRefs.isEmpty()) {
            return MirrorPushResult(pushedRefCount = 0, skippedReadOnlyRefs = skipped)
        }

        val refSpecs = pushRefs.map { ref -> RefSpec("+${ref.name}:${ref.name}") }
        val command = Git(repository)
            .push()
            .setRemote(remoteUri)
            .setForce(true)
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

        MirrorPushResult(
            pushedRefCount = pushRefs.size,
            skippedReadOnlyRefs = skipped,
        )
    }

    private fun isGithubReadOnlyRef(name: String): Boolean =
        name.startsWith(GITHUB_PULL_REF_PREFIX)

    private companion object {
        const val GITHUB_PULL_REF_PREFIX = "refs/pull/"
    }
}
