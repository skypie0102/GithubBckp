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
)

/**
 * Restore primitive for GIT_MIRROR artifacts. It extracts the bare repository,
 * opens it with JGit, and verifies that every advertised ref tip exists in the
 * object database. The restored directory can then be pushed with mirror
 * semantics to a destination repository in a future restore flow.
 */
@Singleton
class GitMirrorRestoreService @Inject constructor() {
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

        FileRepositoryBuilder()
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
                MirrorRestoreResult(
                    refNames = refs.map { it.name }.sorted(),
                    referencedObjectsVerified = objectIds.size,
                )
            }
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
