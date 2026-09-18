package com.skypie0102.githubbckp.mirror

import java.io.File
import java.security.MessageDigest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec

/**
 * Pure Git operations for the new mirror engine.
 *
 * Keeping these operations independent of Android and GitHub authentication
 * makes fetch/prune behavior testable against local repositories.
 */
object GitMirrorOperations {
    fun cloneMirror(
        remoteUri: String,
        destination: File,
        credentialsProvider: CredentialsProvider? = null,
    ) {
        destination.parentFile?.mkdirs()
        val command = Git.cloneRepository()
            .setURI(remoteUri)
            .setDirectory(destination)
            .setMirror(true)
        credentialsProvider?.let(command::setCredentialsProvider)

        command.call().use { git ->
            check(git.repository.isBare) { "Mirror clone did not produce a bare repository" }
        }
    }

    fun fetchAndPrune(
        remoteUri: String,
        repositoryDirectory: File,
        defaultBranch: String,
        credentialsProvider: CredentialsProvider? = null,
    ) {
        openBareRepository(repositoryDirectory).use { repository ->
            Git(repository).use { git ->
                val command = git.fetch()
                    .setRemote(remoteUri)
                    .setRefSpecs(RefSpec(MIRROR_REFSPEC))
                    .setRemoveDeletedRefs(true)
                credentialsProvider?.let(command::setCredentialsProvider)
                command.call()

                val defaultRef = "refs/heads/$defaultBranch"
                if (repository.findRef(defaultRef) != null) {
                    repository.updateRef(Constants.HEAD).link(defaultRef)
                }

                git.gc().call()
            }
        }
    }

    fun remoteRefsDigest(
        remoteUri: String,
        credentialsProvider: CredentialsProvider? = null,
    ): String {
        val command = Git.lsRemoteRepository().setRemote(remoteUri)
        credentialsProvider?.let(command::setCredentialsProvider)
        return digestRefs(command.call())
    }

    fun localRefsDigest(repositoryDirectory: File): String =
        openBareRepository(repositoryDirectory).use { repository ->
            digestRefs(repository.refDatabase.getRefsByPrefix("refs/"))
        }

    fun headCommit(
        repositoryDirectory: File,
        defaultBranch: String,
    ): String? = openBareRepository(repositoryDirectory).use { repository ->
        repository.resolve("refs/heads/$defaultBranch")?.name
    }

    internal fun digestRefs(refs: Collection<Ref>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        refs.asSequence()
            .filter { it.name.startsWith("refs/") }
            .mapNotNull { ref -> ref.objectId?.let { objectId -> "${ref.name} ${objectId.name}" } }
            .sorted()
            .forEach { line ->
                digest.update(line.toByteArray(Charsets.UTF_8))
                digest.update('\n'.code.toByte())
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun openBareRepository(repositoryDirectory: File) =
        FileRepositoryBuilder()
            .setGitDir(repositoryDirectory)
            .setBare()
            .setMustExist(true)
            .build()

    private const val MIRROR_REFSPEC = "+refs/*:refs/*"
}
