package com.skypie0102.githubbckp.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorStorageNamingTest {
    @Test
    fun namesAreReadableWhileKeepingStableRepositoryId() {
        val names = mirrorStorageNames(
            repositoryId = 1367381284L,
            owner = "skypie0102",
            name = "intake-edit",
        )

        assertEquals(
            "skypie0102--intake-edit--1367381284",
            names.folderName,
        )
        assertEquals(
            "skypie0102--intake-edit.tar.gz",
            names.stableFileName,
        )
        assertEquals(
            "skypie0102--intake-edit.pending.tar.gz",
            names.pendingFileName,
        )
    }

    @Test
    fun unsafeFilenameCharactersAreSanitized() {
        val names = mirrorStorageNames(
            repositoryId = 42L,
            owner = "owner space",
            name = "repo:name",
        )

        assertEquals("owner_space--repo_name--42", names.folderName)
        assertEquals("owner_space--repo_name.tar.gz", names.stableFileName)
    }

    @Test
    fun lookupRecognizesOldNumericAndNewReadableFolders() {
        assertTrue(repositoryFolderMatches("1367381284", 1367381284L))
        assertTrue(
            repositoryFolderMatches(
                "skypie0102--intake-edit--1367381284",
                1367381284L,
            ),
        )
        assertFalse(
            repositoryFolderMatches(
                "skypie0102--intake-edit--999",
                1367381284L,
            ),
        )
    }

    @Test
    fun pendingPromotionKeepsReadableArchiveName() {
        assertEquals(
            "skypie0102--intake-edit.tar.gz",
            stableFileNameForPending(
                "skypie0102--intake-edit.pending.tar.gz",
            ),
        )
        assertEquals(
            "mirror.tar.gz",
            stableFileNameForPending("mirror.pending.tar.gz"),
        )
    }

    @Test
    fun stableAndPendingArchiveNamesDoNotOverlap() {
        assertTrue(isStableMirrorArchiveName("owner--repo.tar.gz"))
        assertTrue(isPendingMirrorArchiveName("owner--repo.pending.tar.gz"))
        assertFalse(isStableMirrorArchiveName("owner--repo.pending.tar.gz"))
    }
}
