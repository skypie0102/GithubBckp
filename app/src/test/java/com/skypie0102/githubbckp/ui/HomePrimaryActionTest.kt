package com.skypie0102.githubbckp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePrimaryActionTest {
    @Test
    fun firstTimeSelectionUsesBackupLabel() {
        assertEquals(
            "Back up 2 selected repositories",
            selectedRepositoryActionLabel(
                selectedCount = 2,
                mirroredSelectedCount = 0,
            ),
        )
    }

    @Test
    fun fullyMirroredSelectionUsesUpdateLabel() {
        assertEquals(
            "Update 2 selected repositories",
            selectedRepositoryActionLabel(
                selectedCount = 2,
                mirroredSelectedCount = 2,
            ),
        )
    }

    @Test
    fun mixedSelectionExplainsBothOperations() {
        assertEquals(
            "Back up & update 3 selected repositories",
            selectedRepositoryActionLabel(
                selectedCount = 3,
                mirroredSelectedCount = 2,
            ),
        )
    }

    @Test
    fun singularLabelsAreNatural() {
        assertEquals(
            "Update 1 selected repository",
            selectedRepositoryActionLabel(
                selectedCount = 1,
                mirroredSelectedCount = 1,
            ),
        )
    }
}
