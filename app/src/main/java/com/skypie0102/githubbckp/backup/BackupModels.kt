package com.skypie0102.githubbckp.backup

import java.io.File

data class RepositoryRef(
    val id: Long,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
) {
    val fullName: String = "$owner/$name"
}

enum class BackupType {
    SOURCE_ARCHIVE,
    GIT_MIRROR,
}

enum class BackupStatus {
    QUEUED,
    DOWNLOADING,
    PACKAGING,
    CHECKSUM,
    UPLOADING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class BackupRequest(
    val repository: RepositoryRef,
    val type: BackupType,
)

data class BackupArtifact(
    val repository: RepositoryRef,
    val type: BackupType,
    val file: File,
    val checksumSha256: String,
    val checksumMd5: String,
    val createdAtEpochMs: Long,
    val warnings: List<String> = emptyList(),
)
