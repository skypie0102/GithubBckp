package com.skypie0102.githubbckp.release

import com.skypie0102.githubbckp.mirror.TarGzArchive
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

data class VerifiedLatestReleaseArchive(
    val manifest: LatestReleaseManifest,
    val sizeBytes: Long,
    val sha256: String,
)

object LatestReleaseVerifier {
    fun verify(
        archive: File,
        verificationDirectory: File,
        expectedRepositoryId: Long,
    ): VerifiedLatestReleaseArchive {
        require(archive.isFile) { "Latest release archive is missing" }

        verificationDirectory.deleteRecursively()
        check(verificationDirectory.mkdirs()) {
            "Could not create latest release verification directory"
        }

        try {
            TarGzArchive.extract(archive, verificationDirectory)
            val manifestFile = File(verificationDirectory, LatestReleaseManifest.FILE_NAME)
            check(manifestFile.isFile) { "Latest release manifest is missing" }
            val manifest = LatestReleaseManifest.fromJson(manifestFile.readText())
            check(manifest.formatVersion == LatestReleaseManifest.CURRENT_FORMAT_VERSION) {
                "Unsupported latest release backup format ${manifest.formatVersion}"
            }
            check(manifest.repositoryId == expectedRepositoryId) {
                "Latest release backup belongs to repository ${manifest.repositoryId}, expected $expectedRepositoryId"
            }

            val source = safeChild(
                verificationDirectory,
                manifest.sourceFileName,
            )
            verifyFile(
                file = source,
                expectedSize = manifest.sourceSizeBytes,
                expectedSha256 = manifest.sourceSha256,
                label = "release source archive",
            )

            val seen = mutableSetOf<String>()
            manifest.assets.forEach { asset ->
                check(seen.add(asset.storedName)) {
                    "Latest release manifest contains duplicate asset path: ${asset.storedName}"
                }
                val file = safeChild(verificationDirectory, asset.storedName)
                verifyFile(
                    file = file,
                    expectedSize = asset.sizeBytes,
                    expectedSha256 = asset.sha256,
                    label = "release asset ${asset.originalName}",
                )
            }

            return VerifiedLatestReleaseArchive(
                manifest = manifest,
                sizeBytes = archive.length(),
                sha256 = sha256(archive),
            )
        } finally {
            verificationDirectory.deleteRecursively()
        }
    }

    private fun verifyFile(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
        label: String,
    ) {
        check(file.isFile) { "Missing $label" }
        check(file.length() == expectedSize) {
            "Size mismatch for $label"
        }
        check(sha256(file).equals(expectedSha256, ignoreCase = true)) {
            "SHA-256 mismatch for $label"
        }
    }

    private fun safeChild(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val canonicalChild = File(canonicalRoot, relativePath).canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        if (
            canonicalChild.path != canonicalRoot.path &&
            !canonicalChild.path.startsWith(rootPrefix)
        ) {
            throw IOException("Unsafe latest release path: $relativePath")
        }
        return canonicalChild
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).buffered(256 * 1024).use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
