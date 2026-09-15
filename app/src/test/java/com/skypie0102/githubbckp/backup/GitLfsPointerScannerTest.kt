package com.skypie0102.githubbckp.backup

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitLfsPointerScannerTest {
    private val scanner = GitLfsPointerScanner()

    @Test
    fun findsUniqueLfsPointersAcrossReachableMirrorObjects() = runBlocking {
        val root = Files.createTempDirectory("lfs-pointer-scan").toFile()
        try {
            val source = File(root, "source")
            val oid = "a".repeat(64)
            val pointer = """
                version https://git-lfs.github.com/spec/v1
                oid sha256:$oid
                size 12345
            """.trimIndent() + "\n"

            Git.init().setDirectory(source).call().use { git ->
                File(source, "large.bin").writeText(pointer)
                git.add().addFilepattern("large.bin").call()
                git.commit()
                    .setMessage("add lfs pointer")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                git.tag().setName("v1").call()
            }

            val mirror = File(root, "source.git")
            Git.cloneRepository()
                .setURI(source.toURI().toString())
                .setDirectory(mirror)
                .setMirror(true)
                .call()
                .close()

            assertEquals(listOf(GitLfsPointer(oidSha256 = oid, sizeBytes = 12345L)), scanner.scan(mirror))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedPointer() {
        val malformed = """
            version https://git-lfs.github.com/spec/v1
            oid sha256:not-a-hash
            size 12
        """.trimIndent().toByteArray()

        assertNull(scanner.parsePointer(malformed))
    }
}
