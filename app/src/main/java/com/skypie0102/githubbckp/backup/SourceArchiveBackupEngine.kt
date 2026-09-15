package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubGateway
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceArchiveBackupEngine @Inject constructor(
    private val githubGateway: GithubGateway,
) : BackupEngine {
    override suspend fun createBackup(
        request: BackupRequest,
        workingDirectory: File,
        onProgress: suspend (BackupStatus) -> Unit,
    ): BackupArtifact {
        require(request.type == BackupType.SOURCE_ARCHIVE) {
            "GIT_MIRROR is not implemented yet"
        }
        workingDirectory.mkdirs()
        val createdAt = System.currentTimeMillis()
        val safeName = "${request.repository.owner}-${request.repository.name}"
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val archive = File(workingDirectory, "$safeName-$createdAt.tar.gz")

        onProgress(BackupStatus.DOWNLOADING)
        githubGateway.downloadSourceArchive(
            repository = request.repository,
            ref = request.repository.defaultBranch,
            destination = archive,
        )

        onProgress(BackupStatus.CHECKSUM)
        val (sha256, md5) = digests(archive)
        return BackupArtifact(
            repository = request.repository,
            type = request.type,
            file = archive,
            checksumSha256 = sha256,
            checksumMd5 = md5,
            createdAtEpochMs = createdAt,
        )
    }

    private fun digests(file: File): Pair<String, String> {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha256.update(buffer, 0, count)
                md5.update(buffer, 0, count)
            }
        }
        return sha256.digest().toHex() to md5.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
