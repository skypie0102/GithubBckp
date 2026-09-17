package com.skypie0102.githubbckp.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupEngineFactory @Inject constructor(
    private val gitMirrorBackupEngine: GitMirrorBackupEngine,
) {
    fun forType(type: BackupType): BackupEngine = when (type) {
        BackupType.GIT_MIRROR -> gitMirrorBackupEngine
        BackupType.SOURCE_ARCHIVE -> error("Source snapshots are no longer supported; use a Git mirror")
    }
}
