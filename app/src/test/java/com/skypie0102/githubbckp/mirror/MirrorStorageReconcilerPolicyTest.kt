package com.skypie0102.githubbckp.mirror

import com.skypie0102.githubbckp.data.local.MirrorEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorStorageReconcilerPolicyTest {
    @Test
    fun manuallyDeletedArchiveBecomesMissingWithoutErasingSuccessHistory() {
        val mirror = mirror()

        val reconciled = reconcileMirrorStorageState(
            mirror,
            MirrorStorageState.MISSING,
        )

        assertNull(reconciled.archiveUri)
        assertNull(reconciled.archiveSizeBytes)
        assertNull(reconciled.archiveSha256)
        assertEquals(mirror.lastSuccessfulSyncAtEpochMs, reconciled.lastSuccessfulSyncAtEpochMs)
    }

    @Test
    fun revokedOrMovedFolderBlocksMirrorState() {
        val mirror = mirror()

        val reconciled = reconcileMirrorStorageState(
            mirror,
            MirrorStorageState.UNAVAILABLE,
        )

        assertEquals(MirrorAttemptStatus.BLOCKED.name, reconciled.lastAttemptStatus)
        assertEquals(
            MirrorStorageReconciler.STORAGE_UNAVAILABLE_MESSAGE,
            reconciled.lastWarning,
        )
    }

    @Test
    fun restoredFolderClearsStorageBlock() {
        val blocked = mirror().copy(
            lastAttemptStatus = MirrorAttemptStatus.BLOCKED.name,
            lastWarning = MirrorStorageReconciler.STORAGE_UNAVAILABLE_MESSAGE,
        )

        val reconciled = reconcileMirrorStorageState(
            blocked,
            MirrorStorageState.AVAILABLE,
        )

        assertEquals(MirrorAttemptStatus.COMPLETED.name, reconciled.lastAttemptStatus)
        assertNull(reconciled.lastWarning)
    }

    private fun mirror() = MirrorEntity(
        repositoryId = 7L,
        archiveUri = "content://mirror/7",
        archiveSizeBytes = 4096L,
        archiveSha256 = "sha",
        lastCheckedAtEpochMs = 100L,
        lastSuccessfulSyncAtEpochMs = 100L,
        lastChangedAtEpochMs = 100L,
        lastAttemptAtEpochMs = 100L,
        lastAttemptStatus = MirrorAttemptStatus.COMPLETED.name,
    )
}
