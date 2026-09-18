package com.skypie0102.githubbckp.mirror

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TarGzArchiveTest {
    @Test
    fun createAndExtract_roundTripsNestedFiles() {
        val root = Files.createTempDirectory("tar-gz-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "manifest.json").writeText("""{"ok":true}""")
            File(source, "repository.git/objects/aa").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(16_384) { index -> (index % 251).toByte() })
            }
            File(source, "repository.git/empty").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf())
            }

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(source, archive)

            val extracted = File(root, "extracted")
            TarGzArchive.extract(archive, extracted)

            assertEquals(
                File(source, "manifest.json").readText(),
                File(extracted, "manifest.json").readText(),
            )
            assertArrayEquals(
                File(source, "repository.git/objects/aa").readBytes(),
                File(extracted, "repository.git/objects/aa").readBytes(),
            )
            assertEquals(0L, File(extracted, "repository.git/empty").length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun createAndExtract_preservesUnicodePaths() {
        val root = Files.createTempDirectory("tar-gz-unicode").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val unicode = File(source, "repository.git/对象/こんにちは-🙂.txt").apply {
                parentFile?.mkdirs()
                writeText("unicode-content")
            }

            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(source, archive)

            val extracted = File(root, "extracted")
            TarGzArchive.extract(archive, extracted)

            assertEquals(
                unicode.readText(),
                File(extracted, "repository.git/对象/こんにちは-🙂.txt").readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun create_propagatesWriteFailureDuringCompression() {
        val root = Files.createTempDirectory("tar-gz-write-failure").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "repository.git/objects/large").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(256 * 1024) { index -> ((index * 31) % 251).toByte() })
            }

            assertThrows(IOException::class.java) {
                TarGzArchive.create(
                    sourceDirectory = source,
                    output = FailingOutputStream(maxBytes = 128),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extract_rejectsTruncatedArchive() {
        val root = Files.createTempDirectory("tar-gz-truncated").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            File(source, "large.bin").writeBytes(ByteArray(64 * 1024) { index -> (index % 251).toByte() })
            val archive = File(root, "mirror.tar.gz")
            TarGzArchive.create(source, archive)

            val bytes = archive.readBytes()
            archive.writeBytes(bytes.copyOf(bytes.size / 2))

            assertThrows(IOException::class.java) {
                TarGzArchive.extract(archive, File(root, "destination"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extract_rejectsPathTraversal() {
        val root = Files.createTempDirectory("tar-gz-traversal").toFile()
        try {
            val archive = File(root, "evil.tar.gz")
            FileOutputStream(archive).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { buffered ->
                    GzipCompressorOutputStream(buffered).use { gzip ->
                        TarArchiveOutputStream(gzip).use { tar ->
                            val bytes = "escaped".toByteArray()
                            val entry = TarArchiveEntry("../escape.txt").apply {
                                size = bytes.size.toLong()
                            }
                            tar.putArchiveEntry(entry)
                            tar.write(bytes)
                            tar.closeArchiveEntry()
                            tar.finish()
                        }
                    }
                }
            }

            val destination = File(root, "destination")
            assertThrows(IOException::class.java) {
                TarGzArchive.extract(archive, destination)
            }
            assertFalse(File(root, "escape.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private class FailingOutputStream(
        private val maxBytes: Int,
    ) : OutputStream() {
        private var written = 0

        override fun write(value: Int) {
            requireCapacity(1)
            written += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            requireCapacity(length)
            written += length
        }

        private fun requireCapacity(nextBytes: Int) {
            if (written + nextBytes > maxBytes) {
                throw IOException("Simulated storage exhaustion")
            }
        }
    }
}
