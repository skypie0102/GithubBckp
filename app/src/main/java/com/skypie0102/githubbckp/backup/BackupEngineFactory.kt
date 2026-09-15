package com.skypie0102.githubbckp.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupEngineFactory @Inject constructor(
    private val sourceArchiveBackupEngine: SourceArchiveBackupEngine,
    private val gitMirrorBackupEngine: GitMirrorBackupEngine,
) {
    fun forType(type: BackupType): BackupEngine = when (type) {
        BackupType.SOURCE_ARCHIVE -> sourceArchiveBackupEngine
        BackupType.GIT_MIRROR -> gitMirrorBackupEngine
    }
}
