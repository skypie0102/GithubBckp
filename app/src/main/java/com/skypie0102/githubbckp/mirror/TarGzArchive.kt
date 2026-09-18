package com.skypie0102.githubbckp.mirror

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

/**
 * Creates and extracts the single on-device mirror format used by the refactor.
 *
 * Archives are intentionally simple: regular files and directories only.
 * Symbolic and hard links are rejected during extraction so an archive can
 * never write outside the caller-provided destination.
 */
object TarGzArchive {
    fun create(sourceDirectory: File, destination: File) {
        require(sourceDirectory.isDirectory) {
            "Archive source directory does not exist: ${sourceDirectory.absolutePath}"
        }
        destination.parentFile?.mkdirs()

        FileOutputStream(destination).use { output ->
            create(sourceDirectory, output)
        }

        check(destination.isFile && destination.length() > 0L) {
            "Mirror archive was not created"
        }
    }

    internal fun create(sourceDirectory: File, output: OutputStream) {
        require(sourceDirectory.isDirectory) {
            "Archive source directory does not exist: ${sourceDirectory.absolutePath}"
        }

        BufferedOutputStream(output).use { bufferedOutput ->
            GzipCompressorOutputStream(bufferedOutput).use { gzipOutput ->
                TarArchiveOutputStream(gzipOutput).use { tarOutput ->
                    tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tarOutput.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

                    sourceDirectory.walkTopDown()
                        .drop(1)
                        .sortedBy { it.relativeTo(sourceDirectory).invariantSeparatorsPath }
                        .forEach { file ->
                            require(!Files.isSymbolicLink(file.toPath())) {
                                "Symbolic links are not supported in mirror archives: ${file.absolutePath}"
                            }

                            val relativePath = file.relativeTo(sourceDirectory).invariantSeparatorsPath
                            val entryName = if (file.isDirectory) "$relativePath/" else relativePath
                            val entry = tarOutput.createArchiveEntry(file, entryName)
                            tarOutput.putArchiveEntry(entry)
                            if (file.isFile) {
                                FileInputStream(file).use { input ->
                                    input.copyTo(tarOutput, bufferSize = BUFFER_SIZE)
                                }
                            }
                            tarOutput.closeArchiveEntry()
                        }

                    tarOutput.finish()
                }
            }
        }
    }

    @Throws(IOException::class)
    fun extract(archive: File, destinationDirectory: File) {
        require(archive.isFile) { "Mirror archive does not exist: ${archive.absolutePath}" }

        destinationDirectory.mkdirs()
        val root = destinationDirectory.canonicalFile
        val rootPrefix = root.path + File.separator

        openArchive(archive) { tarInput ->
            while (true) {
                val entry = tarInput.nextTarEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) {
                    throw IOException("Mirror archive contains an unsupported link: ${entry.name}")
                }

                val output = File(root, entry.name).canonicalFile
                if (output.path != root.path && !output.path.startsWith(rootPrefix)) {
                    throw IOException("Mirror archive contains an unsafe path: ${entry.name}")
                }

                if (entry.isDirectory) {
                    if (!output.isDirectory && !output.mkdirs()) {
                        throw IOException("Could not create archive directory: ${entry.name}")
                    }
                } else {
                    output.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs()) {
                            throw IOException("Could not create archive parent: ${entry.name}")
                        }
                    }
                    FileOutputStream(output).use { destination ->
                        tarInput.copyTo(destination, bufferSize = BUFFER_SIZE)
                    }
                }
            }
        }
    }

    /**
     * Reads one small regular-file entry without extracting the full archive.
     * This is used for the manifest so scheduled checks can decide that a
     * mirror is unchanged before allocating temporary extraction space.
     */
    @Throws(IOException::class)
    fun readTextEntry(
        archive: File,
        entryName: String,
        maxBytes: Int = DEFAULT_TEXT_ENTRY_LIMIT,
    ): String = FileInputStream(archive).use { input ->
        readTextEntry(input, entryName, maxBytes)
    }

    /**
     * Stream variant used by Storage Access Framework. It stops as soon as the
     * requested entry is read, so reading manifest.json does not require copying
     * or extracting the complete mirror archive.
     */
    @Throws(IOException::class)
    fun readTextEntry(
        input: InputStream,
        entryName: String,
        maxBytes: Int = DEFAULT_TEXT_ENTRY_LIMIT,
    ): String {
        require(maxBytes > 0) { "maxBytes must be positive" }

        openArchive(input) { tarInput ->
            while (true) {
                val entry = tarInput.nextTarEntry ?: break
                if (entry.name != entryName) continue
                if (entry.isDirectory || entry.isSymbolicLink || entry.isLink) {
                    throw IOException("Archive entry is not a regular file: $entryName")
                }
                if (entry.size > maxBytes.toLong()) {
                    throw IOException("Archive entry is too large: $entryName")
                }

                val output = ByteArrayOutputStream(entry.size.coerceAtLeast(0L).toInt())
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = tarInput.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IOException("Archive entry is too large: $entryName")
                    }
                    output.write(buffer, 0, read)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        }

        throw IOException("Archive entry is missing: $entryName")
    }

    @Throws(IOException::class)
    fun expandedSizeBytes(input: InputStream): Long {
        var total = 0L
        openArchive(input) { tarInput ->
            while (true) {
                val entry = tarInput.nextTarEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) {
                    throw IOException("Mirror archive contains an unsupported link: ${entry.name}")
                }
                if (!entry.isDirectory) {
                    if (entry.size < 0L) {
                        throw IOException("Mirror archive contains an invalid entry size: ${entry.name}")
                    }
                    total = safeAdd(total, entry.size)
                }
            }
        }
        return total
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private inline fun <T> openArchive(
        archive: File,
        block: (TarArchiveInputStream) -> T,
    ): T = FileInputStream(archive).use { input ->
        openArchive(input, block)
    }

    private inline fun <T> openArchive(
        input: InputStream,
        block: (TarArchiveInputStream) -> T,
    ): T {
        BufferedInputStream(input).use { bufferedInput ->
            GzipCompressorInputStream(bufferedInput).use { gzipInput ->
                TarArchiveInputStream(gzipInput).use { tarInput ->
                    return block(tarInput)
                }
            }
        }
    }

    private const val BUFFER_SIZE = 256 * 1024
    private const val DEFAULT_TEXT_ENTRY_LIMIT = 1024 * 1024
}
