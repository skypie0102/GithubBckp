package com.skypie0102.githubbckp.mirror

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryCheckoutExporterTest {
    @Test
    fun export_materializesDefaultBranchFilesOnly() {
        val root = Files.createTempDirectory("repo-checkout").toFile()
        try {
            val source = File(root, "source")
            Git.init()
                .setInitialBranch("main")
                .setDirectory(source)
                .call()
                .use { git ->
                    File(source, "README.md").writeText("main")
                    File(source, "src").mkdirs()
                    File(source, "src/app.txt").writeText("nested")
                    git.add().addFilepattern(".").call()
                    git.commit()
                        .setMessage("main")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()

                    git.checkout().setCreateBranch(true).setName("feature").call()
                    File(source, "feature-only.txt").writeText("feature")
                    git.add().addFilepattern(".").call()
                    git.commit()
                        .setMessage("feature")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                    git.checkout().setName("main").call()
                }

            val mirror = File(root, "mirror.git")
            Git.cloneRepository()
                .setURI(source.toURI().toString())
                .setDirectory(mirror)
                .setMirror(true)
                .call()
                .close()

            val destination = File(root, "repository")
            RepositoryCheckoutExporter.export(mirror, "main", destination)

            assertEquals("main", File(destination, "README.md").readText())
            assertEquals("nested", File(destination, "src/app.txt").readText())
            assertFalse(File(destination, "feature-only.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun export_emptyRepositoryCreatesEmptyBrowsableDirectory() {
        val root = Files.createTempDirectory("repo-checkout-empty").toFile()
        try {
            val mirror = File(root, "empty.git")
            Git.init()
                .setBare(true)
                .setInitialBranch("main")
                .setDirectory(mirror)
                .call()
                .close()

            val destination = File(root, "repository")
            RepositoryCheckoutExporter.export(mirror, "main", destination)

            assertTrue(destination.isDirectory)
            assertEquals(0, destination.listFiles()?.size ?: -1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun export_replacesStaleWorkingTreeContents() {
        val root = Files.createTempDirectory("repo-checkout-stale").toFile()
        try {
            val source = File(root, "source")
            Git.init()
                .setInitialBranch("main")
                .setDirectory(source)
                .call()
                .use { git ->
                    File(source, "current.txt").writeText("current")
                    git.add().addFilepattern(".").call()
                    git.commit()
                        .setMessage("main")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                }

            val mirror = File(root, "mirror.git")
            Git.cloneRepository()
                .setURI(source.toURI().toString())
                .setDirectory(mirror)
                .setMirror(true)
                .call()
                .close()

            val destination = File(root, "repository").apply {
                mkdirs()
                File(this, "stale.txt").writeText("stale")
            }

            RepositoryCheckoutExporter.export(mirror, "main", destination)

            assertFalse(File(destination, "stale.txt").exists())
            assertEquals("current", File(destination, "current.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun export_symlinkBlobIsStoredAsSafeRegularFile() {
        val root = Files.createTempDirectory("repo-checkout-symlink").toFile()
        try {
            val source = File(root, "source")
            Git.init()
                .setInitialBranch("main")
                .setDirectory(source)
                .call()
                .use { git ->
                    File(source, "target.txt").writeText("target")
                    val link = File(source, "link.txt").toPath()
                    runCatching {
                        Files.createSymbolicLink(link, java.nio.file.Path.of("target.txt"))
                    }.getOrElse {
                        return
                    }
                    git.add().addFilepattern(".").call()
                    git.commit()
                        .setMessage("symlink")
                        .setAuthor("Test", "test@example.com")
                        .setCommitter("Test", "test@example.com")
                        .call()
                }

            val mirror = File(root, "mirror.git")
            Git.cloneRepository()
                .setURI(source.toURI().toString())
                .setDirectory(mirror)
                .setMirror(true)
                .call()
                .close()

            val destination = File(root, "repository")
            RepositoryCheckoutExporter.export(mirror, "main", destination)

            val exported = File(destination, "link.txt")
            assertTrue(exported.isFile)
            assertFalse(Files.isSymbolicLink(exported.toPath()))
            assertEquals("target.txt", exported.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
