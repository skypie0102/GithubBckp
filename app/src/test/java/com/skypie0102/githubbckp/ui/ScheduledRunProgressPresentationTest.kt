package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.worker.ScheduledBackupRunProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduledRunProgressPresentationTest {
    @Test
    fun activeRunShowsObservedAndUnobservedStatesWithoutCallingThemFailures() {
        val progress = progress(
            expected = 5,
            observed = 4,
            completed = 2,
            failed = 1,
            active = 1,
            unobserved = 1,
        )

        assertEquals(
            "Progress: 2 completed • 1 failed • 1 active • 1 not started or deduplicated.",
            progress.progressDisplayText(),
        )
    }

    @Test
    fun fullyObservedTerminalRunUsesResultWording() {
        val progress = progress(
            expected = 4,
            observed = 4,
            completed = 3,
            cancelled = 1,
        )

        assertEquals(
            "Run result: 3 completed • 1 cancelled.",
            progress.progressDisplayText(),
        )
    }

    @Test
    fun emptyObservedRunStaysNonFailureAndShowsDeduplicatedPossibility() {
        val progress = progress(
            expected = 3,
            observed = 0,
            unobserved = 3,
        )

        assertEquals(
            "Progress: 3 not started or deduplicated.",
            progress.progressDisplayText(),
        )
    }

    private fun progress(
        expected: Int,
        observed: Int,
        active: Int = 0,
        completed: Int = 0,
        failed: Int = 0,
        cancelled: Int = 0,
        unobserved: Int = 0,
    ) = ScheduledBackupRunProgress(
        scheduledRunId = "run-1",
        expectedRepositoryCount = expected,
        observedRepositoryCount = observed,
        activeCount = active,
        completedCount = completed,
        failedCount = failed,
        cancelledCount = cancelled,
        unobservedCount = unobserved,
    )
}
