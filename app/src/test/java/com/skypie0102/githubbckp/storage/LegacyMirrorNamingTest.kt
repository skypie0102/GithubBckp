package com.skypie0102.githubbckp.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMirrorNamingTest {
    @Test
    fun matchesOnlyOldAppOwnedMirrorNames() {
        assertTrue(isLegacyMirrorFileName("octo", "repo", "octo-repo.mirror.zip"))
        assertTrue(
            isLegacyMirrorFileName(
                "octo",
                "repo",
                "octo-repo.pending-123456.mirror.zip",
            ),
        )

        assertFalse(isLegacyMirrorFileName("octo", "repo", "notes.zip"))
        assertFalse(isLegacyMirrorFileName("octo", "repo", "other-repo.mirror.zip"))
        assertFalse(isLegacyMirrorFileName("octo", "repo", "octo-repo.tar.gz"))
    }

    @Test
    fun usesSameSanitizationAsLegacyBackupEngine() {
        assertTrue(
            isLegacyMirrorFileName(
                "owner space",
                "repo:name",
                "owner_space-repo_name.mirror.zip",
            ),
        )
    }
}
