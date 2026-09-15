package com.skypie0102.githubbckp.backup

import java.io.File

interface BackupEngine {
    suspend fun createBackup(
        request: BackupRequest,
        workingDirectory: File,
        onProgress: suspend (BackupStatus) -> Unit = {},
    ): BackupArtifact
}
