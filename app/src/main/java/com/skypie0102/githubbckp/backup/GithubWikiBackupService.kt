package com.skypie0102.githubbckp.backup

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

data class GithubWikiVerification(
    val refNames: List<String>,
    val referencedObjectsVerified: Int,
)

@Singleton
class GithubWikiBackupService @Inject constructor() {
    fun backup(
        repository: RepositoryRef,
        accessToken: String,
        destination: File,
    ): GithubWikiVerification? {
        destination.deleteRecursively()
        destination.parentFile?.mkdirs()
        val credentials = UsernamePasswordCredentialsProvider("x-access-token", accessToken)
        return try {
            Git.cloneRepository()
                .setURI("https://github.com/${repository.fullName}.wiki.git")
                .setDirectory(destination)
                .setMirror(true)
                .setCredentialsProvider(credentials)
                .call()
                .use { git ->
                    check(git.repository.isBare) { "Wiki mirror clone did not produce a bare repository" }
                }
            verifyBareWiki(destination)
        } catch (throwable: TransportException) {
            destination.deleteRecursively()
            if (isUninitializedWiki(throwable)) null else throw throwable
        }
    }

    fun validateBundledWiki(repositoryDirectory: File): GithubWikiVerification? {
        val wikiDirectory = File(repositoryDirectory, BUNDLED_WIKI_DIRECTORY)
        if (!wikiDirectory.exists()) return null
        return verifyBareWiki(wikiDirectory)
    }

    private fun verifyBareWiki(directory: File): GithubWikiVerification {
        check(File(directory, "HEAD").isFile) { "Bundled wiki mirror is missing HEAD" }
        check(File(directory, "objects").isDirectory) { "Bundled wiki mirror is missing objects" }
        FileRepositoryBuilder()
            .setGitDir(directory)
            .setBare()
            .build()
            .use { repository ->
                check(repository.isBare) { "Bundled wiki repository is not bare" }
                val refs = repository.refDatabase.getRefsByPrefix("refs/")
                check(refs.isNotEmpty()) { "Bundled wiki mirror contains no refs" }
                val objectIds = refs.mapNotNull { it.objectId }.distinct()
                objectIds.forEach { objectId ->
                    check(repository.objectDatabase.has(objectId)) {
                        "Bundled wiki mirror is missing object ${objectId.name}"
                    }
                }
                return GithubWikiVerification(
                    refNames = refs.map { it.name }.sorted(),
                    referencedObjectsVerified = objectIds.size,
                )
            }
    }

    private fun isUninitializedWiki(throwable: TransportException): Boolean {
        val message = generateSequence<Throwable>(throwable) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return "not found" in message || "does not exist" in message || "no remote repository" in message
    }

    companion object {
        const val BUNDLED_WIKI_DIRECTORY = "github-backup/wiki.git"
    }
}
