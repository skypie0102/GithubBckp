package com.skypie0102.githubbckp.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitMirrorRestoreServiceTest {
    private val service = GitMirrorRestoreService()

    @Test
    fun restoresMirrorAndVerifiesAdvertisedRefs() = runBlocking {
        val root = Files.createTempDirectory("mirror-restore-test").toFile()
        try {
            val source = File(root, "source")
            Git.init().setDirectory(source).call().use { git ->
                File(source, "README.md").writeText("backup test\n")
                git.add().addFilepattern("README.md").call()
                git.commit()
                    .setMessage("initial")
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

            val archive = File(root, "source.mirror.zip")
            zipDirectory(mirror, archive)
            val restored = File(root, "restored.git")
            val result = service.restore(archive, restored)

            assertTrue(result.refNames.any { it.startsWith("refs/heads/") })
            assertTrue(result.refNames.contains("refs/tags/v1"))
            assertTrue(result.referencedObjectsVerified >= 2)
            assertTrue(File(restored, "HEAD").isFile)
            assertTrue(File(restored, "objects").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipEntryEscapingRestoreDirectory() = runBlocking {
        val root = Files.createTempDirectory("mirror-escape-test").toFile()
        try {
            val archive = File(root, "malicious.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.txt"))
                zip.write("nope".toByteArray())
                zip.closeEntry()
            }

            val destination = File(root, "restore")
            try {
                service.restore(archive, destination)
                fail("Expected unsafe archive entry to be rejected")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message.orEmpty().contains("Unsafe mirror archive entry"))
            }
            assertFalse(File(root, "escape.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipDirectory(source: File, destination: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { zip ->
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}
