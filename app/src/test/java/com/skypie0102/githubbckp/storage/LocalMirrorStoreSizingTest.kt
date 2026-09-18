package com.skypie0102.githubbckp.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMirrorStoreSizingTest {
    @Test
    fun updateWorkspaceIncludesArchiveCopiesExpansionAndHeadroom() {
        val mib = 1024L * 1024L
        assertEquals(
            2L * 100L * mib + 400L * mib + 100L * mib,
            requiredUpdateWorkspaceBytes(
                compressedBytes = 100L * mib,
                expandedBytes = 400L * mib,
            ),
        )
    }

    @Test
    fun updateWorkspaceUsesMinimumHeadroomForSmallMirrors() {
        val mib = 1024L * 1024L
        assertEquals(
            2L * 10L * mib + 20L * mib + 64L * mib,
            requiredUpdateWorkspaceBytes(
                compressedBytes = 10L * mib,
                expandedBytes = 20L * mib,
            ),
        )
    }

    @Test
    fun legacyCheckoutUpgradeAddsConservativeReserve() {
        val mib = 1024L * 1024L
        assertEquals(
            2L * 100L * mib + 400L * mib + 100L * mib + 800L * mib,
            requiredUpdateWorkspaceBytes(
                compressedBytes = 100L * mib,
                expandedBytes = 400L * mib,
                reserveBrowsableCheckoutUpgrade = true,
            ),
        )
    }

    @Test
    fun smallLegacyCheckoutUpgradeUsesMinimumReserve() {
        val mib = 1024L * 1024L
        assertEquals(
            2L * 10L * mib + 20L * mib + 64L * mib + 128L * mib,
            requiredUpdateWorkspaceBytes(
                compressedBytes = 10L * mib,
                expandedBytes = 20L * mib,
                reserveBrowsableCheckoutUpgrade = true,
            ),
        )
    }

    @Test
    fun updateWorkspaceSaturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            requiredUpdateWorkspaceBytes(
                compressedBytes = Long.MAX_VALUE,
                expandedBytes = Long.MAX_VALUE,
            ),
        )
    }
}
