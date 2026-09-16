package com.skypie0102.githubbckp.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitMirrorRestoreServiceTest {
    private val service = GitMirrorRestoreService(
        GithubWikiBackupService(),
        GithubReleaseBackupService(),
        GithubDiscussionBackupService(),
    )

    @Test
    fun restoresMirrorAndVerifiesAdvertisedRefsWikiAndReleases() = runBlocking {
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

            val wikiSource = File(root, "wiki-source")
            Git.init().setDirectory(wikiSource).call().use { git ->
                File(wikiSource, "Home.md").writeText("# Wiki\n")
                git.add().addFilepattern("Home.md").call()
                git.commit()
                    .setMessage("wiki home")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
            }
            val wikiMirror = File(mirror, GithubWikiBackupService.BUNDLED_WIKI_DIRECTORY)
            wikiMirror.parentFile?.mkdirs()
            Git.cloneRepository()
                .setURI(wikiSource.toURI().toString())
                .setDirectory(wikiMirror)
                .setMirror(true)
                .call()
                .close()

            addBundledRelease(mirror)

            val archive = File(root, "source.mirror.zip")
            zipDirectory(mirror, archive)
            val restored = File(root, "restored.git")
            val result = service.restore(archive, restored)

            assertTrue(result.refNames.any { it.startsWith("refs/heads/") })
            assertTrue(result.refNames.contains("refs/tags/v1"))
            assertTrue(result.referencedObjectsVerified >= 2)
            assertTrue(result.wikiRefNames.any { it.startsWith("refs/heads/") })
            assertTrue(result.wikiReferencedObjectsVerified >= 1)
            assertTrue(result.releaseCount == 1)
            assertTrue(result.releaseAssetCount == 1)
            assertTrue(File(restored, "HEAD").isFile)
            assertTrue(File(restored, "objects").isDirectory)
            assertTrue(File(restored, "${GithubWikiBackupService.BUNDLED_WIKI_DIRECTORY}/HEAD").isFile)
            assertTrue(File(restored, "${GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY}/${GithubReleaseBackupService.MANIFEST_FILE_NAME}").isFile)
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

    private fun addBundledRelease(mirror: File) {
        val releases = File(mirror, GithubReleaseBackupService.BUNDLED_RELEASES_DIRECTORY)
        val bytes = "release binary".toByteArray()
        val relativePath = "assets/release-1/10-release.bin"
        File(releases, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = JSONObject()
            .put("formatVersion", 1)
            .put(
                "releases",
                JSONArray().put(
                    JSONObject()
                        .put("sourceId", 1)
                        .put("tagName", "v1")
                        .put(
                            "assets",
                            JSONArray().put(
                                JSONObject()
                                    .put("sourceId", 10)
                                    .put("name", "release.bin")
                                    .put("size", bytes.size)
                                    .put("sha256", sha256)
                                    .put("relativePath", relativePath),
                            ),
                        ),
                ),
            )
        releases.mkdirs()
        File(releases, GithubReleaseBackupService.MANIFEST_FILE_NAME).writeText(manifest.toString())
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
