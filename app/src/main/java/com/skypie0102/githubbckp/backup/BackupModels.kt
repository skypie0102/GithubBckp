package com.skypie0102.githubbckp.backup

data class RepositoryRef(
    val id: Long,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
) {
    val fullName: String = "$owner/$name"
}

enum class BackupOrigin {
    MANUAL,
    SCHEDULED,
}

enum class MirrorStatus {
    IDLE,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class MirrorBackupRequest(
    val repository: RepositoryRef,
    val origin: BackupOrigin,
)

data class MirrorBuildResult(
    val commitSha: String,
    val archiveFile: java.io.File,
)
