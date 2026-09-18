package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubAuthManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

@Singleton
class MirrorBackupEngine @Inject constructor(
    private val authManager: GithubAuthManager,
    private val tarGzArchive: TarGzArchive,
) {
    suspend fun build(
        request: MirrorBackupRequest,
        existingArchive: File?,
        workDirectory: File,
    ): MirrorBuildResult = withContext(Dispatchers.IO) {
        workDirectory.deleteRecursively()
        workDirectory.mkdirs()

        val repositoryDirectory = File(workDirectory, "repository")
        val outputArchive = File(workDirectory, "mirror.tar.gz")
        val token = authManager.requireAccessToken()
        val credentials = UsernamePasswordCredentialsProvider("x-access-token", token)

        if (existingArchive != null && existingArchive.isFile) {
            tarGzArchive.extract(existingArchive, repositoryDirectory)
            updateExistingRepository(
                repository = request.repository,
                repositoryDirectory = repositoryDirectory,
                credentials = credentials,
            )
        } else {
            cloneRepository(
                repository = request.repository,
                repositoryDirectory = repositoryDirectory,
                credentials = credentials,
            )
        }

        val commitSha = Git.open(repositoryDirectory).use { git ->
            val defaultRef = "refs/remotes/origin/${request.repository.defaultBranch}"
            git.repository.resolve(defaultRef)?.name
                ?: git.repository.resolve("HEAD")?.name
                ?: error("Unable to resolve the backed-up commit")
        }

        tarGzArchive.compressDirectory(repositoryDirectory, outputArchive)
        repositoryDirectory.deleteRecursively()

        MirrorBuildResult(
            commitSha = commitSha,
            archiveFile = outputArchive,
        )
    }

    private fun cloneRepository(
        repository: RepositoryRef,
        repositoryDirectory: File,
        credentials: UsernamePasswordCredentialsProvider,
    ) {
        Git.cloneRepository()
            .setURI(repositoryUrl(repository))
            .setDirectory(repositoryDirectory)
            .setCloneAllBranches(true)
            .setCredentialsProvider(credentials)
            .call()
            .use { git ->
                hardResetDefaultBranch(git, repository)
            }
    }

    private fun updateExistingRepository(
        repository: RepositoryRef,
        repositoryDirectory: File,
        credentials: UsernamePasswordCredentialsProvider,
    ) {
        check(File(repositoryDirectory, ".git").isDirectory) {
            "Existing archive is not a valid working Git repository"
        }

        Git.open(repositoryDirectory).use { git ->
            git.repository.config.apply {
                setString("remote", "origin", "url", repositoryUrl(repository))
                save()
            }

            git.fetch()
                .setRemote("origin")
                .setCredentialsProvider(credentials)
                .setRemoveDeletedRefs(true)
                .setRefSpecs(
                    RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                    RefSpec("+refs/tags/*:refs/tags/*"),
                )
                .call()

            hardResetDefaultBranch(git, repository)
            git.clean()
                .setCleanDirectories(true)
                .setForce(true)
                .call()
        }
    }

    private fun hardResetDefaultBranch(git: Git, repository: RepositoryRef) {
        val branch = repository.defaultBranch
        val remoteRef = "refs/remotes/origin/$branch"
        check(git.repository.resolve(remoteRef) != null) {
            "GitHub default branch $branch was not fetched"
        }

        if (git.repository.resolve("refs/heads/$branch") == null) {
            git.checkout()
                .setCreateBranch(true)
                .setName(branch)
                .setStartPoint(remoteRef)
                .call()
        } else {
            git.checkout()
                .setName(branch)
                .setForced(true)
                .call()
        }

        git.reset()
            .setMode(ResetCommand.ResetType.HARD)
            .setRef(remoteRef)
            .call()
    }

    private fun repositoryUrl(repository: RepositoryRef): String =
        "https://github.com/${repository.fullName}.git"
}
