package com.skypie0102.githubbckp.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

data class MirrorRestoreResult(
    val refNames: List<String>,
    val referencedObjectsVerified: Int,
    val lfsObjectCount: Int = 0,
    val wikiRefNames: List<String> = emptyList(),
    val wikiReferencedObjectsVerified: Int = 0,
    val releaseCount: Int = 0,
    val releaseAssetCount: Int = 0,
    val issueCount: Int = 0,
    val pullRequestCount: Int = 0,
    val issueCommentCount: Int = 0,
    val reviewCommentCount: Int = 0,
    val reviewCount: Int = 0,
)

/**
 * Restore primitive for GIT_MIRROR artifacts. It extracts the bare repository,
 * verifies advertised main-repository ref tips, every referenced bundled LFS
 * object, and each bundled metadata module before retaining the local restore.
 */
@Singleton
class GitMirrorRestoreService @Inject constructor(
    private val lfsPointerScanner: GitLfsPointerScanner,
    private val lfsObjectStore: GitLfsObjectStore,
    private val wikiBackupService: GithubWikiBackupService,
    private val releaseBackupService: GithubReleaseBackupService,
    private val discussionBackupService: GithubDiscussionBackupService,
) {
    suspend fun restore(
        archive: File,
        destination: File,
    ): MirrorRestoreResult = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Mirror archive does not exist" }
        require(!destination.exists() || destination.listFiles().isNullOrEmpty()) {
            "Restore destination must be empty"
        }
        destination.mkdirs()
        extractSafely(archive, destination)

        check(File(destination, "HEAD").isFile) { "Restored mirror is missing HEAD" }
        check(File(destination, "objects").isDirectory) { "Restored mirror is missing objects" }

        val mainResult = FileRepositoryBuilder()
            .setGitDir(destination)
            .setBare()
            .build()
            .use { repository ->
                check(repository.isBare) { "Restored repository is not bare" }
                val refs = repository.refDatabase.getRefsByPrefix("refs/")
                val objectIds = refs.mapNotNull { it.objectId }.distinct()
                objectIds.forEach { objectId ->
                    check(repository.objectDatabase.has(objectId)) {
                        "Mirror is missing object ${objectId.name}"
                    }
                }
                refs.map { it.name }.sorted() to objectIds.size
            }

        val lfsPointers = lfsPointerScanner.scan(destination)
        lfsPointers.forEach { pointer ->
            lfsObjectStore.requireVerifiedObject(destination, pointer)
        }

        val wikiResult = wikiBackupService.validateBundledWiki(destination)
        val releaseResult = releaseBackupService.validateBundledReleases(destination)
        val discussionResult = discussionBackupService.validateBundledDiscussions(destination)

        MirrorRestoreResult(
            refNames = mainResult.first,
            referencedObjectsVerified = mainResult.second,
            lfsObjectCount = lfsPointers.size,
            wikiRefNames = wikiResult?.refNames.orEmpty(),
            wikiReferencedObjectsVerified = wikiResult?.referencedObjectsVerified ?: 0,
            releaseCount = releaseResult?.releaseCount ?: 0,
            releaseAssetCount = releaseResult?.assetCount ?: 0,
            issueCount = discussionResult?.issueCount ?: 0,
            pullRequestCount = discussionResult?.pullRequestCount ?: 0,
            issueCommentCount = discussionResult?.issueCommentCount ?: 0,
            reviewCommentCount = discussionResult?.reviewCommentCount ?: 0,
            reviewCount = discussionResult?.reviewCount ?: 0,
        )
    }

    suspend fun validate(archive: File, scratchDirectory: File): MirrorRestoreResult {
        val restoreDirectory = File(scratchDirectory, "mirror-restore-${System.nanoTime()}")
        return try {
            restore(archive, restoreDirectory)
        } finally {
            restoreDirectory.deleteRecursively()
        }
    }

    private fun extractSafely(archive: File, destination: File) {
        val destinationRoot = destination.canonicalFile
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destinationRoot, entry.name).canonicalFile
                val allowedPrefix = destinationRoot.path + File.separator
                if (output != destinationRoot && !output.path.startsWith(allowedPrefix)) {
                    throw IOException("Unsafe mirror archive entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).buffered().use { stream ->
                        zip.copyTo(stream, bufferSize = 128 * 1024)
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
