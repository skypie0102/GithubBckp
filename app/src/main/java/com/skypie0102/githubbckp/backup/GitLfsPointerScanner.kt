package com.skypie0102.githubbckp.backup

import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.revwalk.RevBlob
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

data class GitLfsPointer(
    val oidSha256: String,
    val sizeBytes: Long,
)

@Singleton
class GitLfsPointerScanner @Inject constructor() {
    fun scan(repositoryDirectory: File): List<GitLfsPointer> {
        require(repositoryDirectory.isDirectory) { "Mirror repository directory is missing" }
        FileRepositoryBuilder()
            .setGitDir(repositoryDirectory)
            .setBare()
            .build()
            .use { repository ->
                ObjectWalk(repository).use { walk ->
                    repository.refDatabase.getRefsByPrefix("refs/")
                        .mapNotNull { it.objectId }
                        .forEach { objectId -> walk.markStart(walk.parseAny(objectId)) }

                    while (walk.next() != null) {
                        // Drain commits first; ObjectWalk schedules reachable trees/blobs.
                    }

                    val pointers = linkedMapOf<String, GitLfsPointer>()
                    while (true) {
                        val gitObject = walk.nextObject() ?: break
                        if (gitObject !is RevBlob) continue

                        val loader = walk.objectReader.open(gitObject, Constants.OBJ_BLOB)
                        if (loader.size <= 0L || loader.size >= MAX_POINTER_BYTES) continue
                        val bytes = loader.openStream().use { it.readBytes() }
                        val pointer = parsePointer(bytes) ?: continue
                        val previous = pointers[pointer.oidSha256]
                        check(previous == null || previous.sizeBytes == pointer.sizeBytes) {
                            "Conflicting Git LFS sizes for ${pointer.oidSha256}"
                        }
                        pointers[pointer.oidSha256] = pointer
                    }
                    return pointers.values.sortedBy { it.oidSha256 }
                }
            }
    }

    internal fun parsePointer(bytes: ByteArray): GitLfsPointer? {
        if (bytes.isEmpty() || bytes.size >= MAX_POINTER_BYTES) return null
        val text = bytes.toString(StandardCharsets.UTF_8)
        val lines = text.split('\n')
        if (lines.isEmpty()) return null

        val version = lines.firstOrNull()?.removePrefix("version ")
        if (version !in SUPPORTED_POINTER_VERSIONS) return null

        val oidValue = lines.firstOrNull { it.startsWith("oid ") }
            ?.removePrefix("oid ")
            ?: return null
        if (!oidValue.startsWith(SHA256_PREFIX)) return null
        val oid = oidValue.removePrefix(SHA256_PREFIX)
        if (!SHA256_REGEX.matches(oid)) return null

        val sizeValue = lines.firstOrNull { it.startsWith("size ") }
            ?.removePrefix("size ")
            ?: return null
        val size = sizeValue.toLongOrNull()?.takeIf { it >= 0L } ?: return null

        return GitLfsPointer(oidSha256 = oid, sizeBytes = size)
    }

    private companion object {
        const val MAX_POINTER_BYTES = 1024L
        const val SHA256_PREFIX = "sha256:"
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
        val SUPPORTED_POINTER_VERSIONS = setOf(
            "https://git-lfs.github.com/spec/v1",
            "https://hawser.github.com/spec/v1",
            "http://git-media.io/v/2",
        )
    }
}
