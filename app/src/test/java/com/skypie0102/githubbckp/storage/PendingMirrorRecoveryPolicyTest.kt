package com.skypie0102.githubbckp.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingMirrorRecoveryPolicyTest {
    @Test
    fun crashBeforeStableRetirementKeepsStableAndDiscardsCandidate() {
        assertEquals(
            PendingMirrorRecoveryAction.DISCARD_PENDING,
            pendingMirrorRecoveryAction(
                stableExists = true,
                pendingExists = true,
            ),
        )
    }

    @Test
    fun crashAfterStableRetirementRequiresPendingVerificationAndPromotion() {
        assertEquals(
            PendingMirrorRecoveryAction.VERIFY_AND_PROMOTE,
            pendingMirrorRecoveryAction(
                stableExists = false,
                pendingExists = true,
            ),
        )
    }

    @Test
    fun completedPromotionNeedsNoRecovery() {
        assertEquals(
            PendingMirrorRecoveryAction.NONE,
            pendingMirrorRecoveryAction(
                stableExists = true,
                pendingExists = false,
            ),
        )
    }

    @Test
    fun emptyFolderNeedsNoRecovery() {
        assertEquals(
            PendingMirrorRecoveryAction.NONE,
            pendingMirrorRecoveryAction(
                stableExists = false,
                pendingExists = false,
            ),
        )
    }
}
