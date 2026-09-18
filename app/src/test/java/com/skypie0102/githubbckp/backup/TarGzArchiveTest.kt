package com.skypie0102.githubbckp.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TarGzArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripPreservesRepositoryFilesIncludingDotGit() {
        val source = temporaryFolder.newFolder("source")
        File(source, ".git").mkdirs()
        File(source, ".git/config").writeText("[core]\nrepositoryformatversion = 0\n")
        File(source, "README.md").writeText("hello")
        File(source, "src").mkdirs()
        File(source, "src/App.kt").writeText("fun main() = Unit")

        val archive = File(temporaryFolder.root, "mirror.tar.gz")
        val restored = temporaryFolder.newFolder("restored")
        val tar = TarGzArchive()

        tar.compressDirectory(source, archive)
        tar.extract(archive, restored)

        assertTrue(archive.length() > 0L)
        assertEquals("hello", File(restored, "README.md").readText())
        assertTrue(File(restored, ".git/config").readText().contains("repositoryformatversion"))
        assertEquals("fun main() = Unit", File(restored, "src/App.kt").readText())
    }
}
