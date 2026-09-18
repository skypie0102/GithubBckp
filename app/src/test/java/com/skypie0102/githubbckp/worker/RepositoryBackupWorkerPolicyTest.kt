package com.skypie0102.githubbckp.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryBackupWorkerPolicyTest {
    @Test
    fun successfulMirrorNeverRetries() {
        assertEquals(
            MirrorWorkDisposition.SUCCESS,
            mirrorWorkDisposition(success = true, runAttemptCount = 0),
        )
        assertEquals(
            MirrorWorkDisposition.SUCCESS,
            mirrorWorkDisposition(success = true, runAttemptCount = 99),
        )
    }

    @Test
    fun failedMirrorRetriesTwiceThenBecomesFinalFailure() {
        assertEquals(
            MirrorWorkDisposition.RETRY,
            mirrorWorkDisposition(success = false, runAttemptCount = 0),
        )
        assertEquals(
            MirrorWorkDisposition.RETRY,
            mirrorWorkDisposition(success = false, runAttemptCount = 1),
        )
        assertEquals(
            MirrorWorkDisposition.FAILURE,
            mirrorWorkDisposition(success = false, runAttemptCount = 2),
        )
    }
}
