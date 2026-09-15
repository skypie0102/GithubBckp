package com.skypie0102.githubbckp.backup

import com.skypie0102.githubbckp.github.GithubGateway
import java.io.File
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
            "SourceArchiveBackupEngine only handles SOURCE_ARCHIVE"
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
        val digests = calculateDigests(archive)
        return BackupArtifact(
            repository = request.repository,
            type = request.type,
            file = archive,
            checksumSha256 = digests.sha256,
            checksumMd5 = digests.md5,
            createdAtEpochMs = createdAt,
        )
    }
}
