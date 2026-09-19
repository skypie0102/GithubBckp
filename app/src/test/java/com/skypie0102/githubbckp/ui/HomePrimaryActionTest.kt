package com.skypie0102.githubbckp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePrimaryActionTest {
    @Test
    fun noUpdatesUsesDisabledLabel() {
        assertEquals("No updates available", updateAvailableActionLabel(0))
    }

    @Test
    fun oneUpdateUsesSingularLabel() {
        assertEquals(
            "Update 1 available repository",
            updateAvailableActionLabel(1),
        )
    }

    @Test
    fun multipleUpdatesUseOnlyAvailableCount() {
        assertEquals(
            "Update 3 available repositories",
            updateAvailableActionLabel(3),
        )
    }

    @Test
    fun missingMirrorBackupIsSeparateFromUpdateAction() {
        assertEquals(
            "Back up 2 repositories without a mirror",
            missingMirrorActionLabel(2),
        )
    }
}
