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
    val wikiRefNames: List<String> = emptyList(),
    val wikiReferencedObjectsVerified: Int = 0,
)

/**
 * Restore primitive for GIT_MIRROR artifacts. It extracts the bare repository,
 * opens it with JGit, verifies advertised main-repository ref tips, and also
 * verifies the bundled wiki mirror when one is present.
 */
@Singleton
class GitMirrorRestoreService @Inject constructor(
    private val wikiBackupService: GithubWikiBackupService,
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
        val wikiResult = wikiBackupService.validateBundledWiki(destination)

        MirrorRestoreResult(
            refNames = mainResult.first,
            referencedObjectsVerified = mainResult.second,
            wikiRefNames = wikiResult?.refNames.orEmpty(),
            wikiReferencedObjectsVerified = wikiResult?.referencedObjectsVerified ?: 0,
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
