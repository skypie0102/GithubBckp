package com.skypie0102.githubbckp.mirror

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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

        FileOutputStream(destination).use { fileOutput ->
            BufferedOutputStream(fileOutput).use { bufferedOutput ->
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

        check(destination.isFile && destination.length() > 0L) {
            "Mirror archive was not created"
        }
    }

    @Throws(IOException::class)
    fun extract(archive: File, destinationDirectory: File) {
        require(archive.isFile) { "Mirror archive does not exist: ${archive.absolutePath}" }

        destinationDirectory.mkdirs()
        val root = destinationDirectory.canonicalFile
        val rootPrefix = root.path + File.separator

        FileInputStream(archive).use { fileInput ->
            BufferedInputStream(fileInput).use { bufferedInput ->
                GzipCompressorInputStream(bufferedInput).use { gzipInput ->
                    TarArchiveInputStream(gzipInput).use { tarInput ->
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
            }
        }
    }

    private const val BUFFER_SIZE = 256 * 1024
}
