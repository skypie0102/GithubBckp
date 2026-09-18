package com.skypie0102.githubbckp.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

@Singleton
class TarGzArchive @Inject constructor() {
    fun compressDirectory(source: File, destination: File) {
        require(source.isDirectory) { "Mirror working directory is missing" }
        destination.parentFile?.mkdirs()

        TarArchiveOutputStream(
            GzipCompressorOutputStream(
                BufferedOutputStream(FileOutputStream(destination), BUFFER_SIZE),
            ),
        ).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)

            source.walkTopDown().drop(1).forEach { file ->
                val relative = file.relativeTo(source).invariantSeparatorsPath +
                    if (file.isDirectory) "/" else ""
                val entry = tar.createArchiveEntry(file, relative)
                tar.putArchiveEntry(entry)
                if (file.isFile) {
                    FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
                        input.copyTo(tar, BUFFER_SIZE)
                    }
                }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        check(destination.length() > 0L) { "Created tar.gz archive is empty" }
    }

    fun extract(archive: File, destination: File) {
        require(archive.isFile) { "Existing mirror archive is missing" }
        destination.mkdirs()
        val destinationRoot = destination.canonicalFile

        TarArchiveInputStream(
            GzipCompressorInputStream(
                BufferedInputStream(FileInputStream(archive), BUFFER_SIZE),
            ),
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                if (output != destinationRoot && !output.path.startsWith(destinationRoot.path + File.separator)) {
                    throw IOException("Unsafe path in mirror archive: ${entry.name}")
                }

                when {
                    entry.isDirectory -> output.mkdirs()
                    entry.isFile -> {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).buffered(BUFFER_SIZE).use { stream ->
                            tar.copyTo(stream, BUFFER_SIZE)
                        }
                        output.setLastModified(entry.modTime.time)
                    }
                }
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
    }
}
