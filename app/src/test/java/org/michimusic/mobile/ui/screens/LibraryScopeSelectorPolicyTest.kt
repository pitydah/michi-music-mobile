package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScopeSelectorPolicyTest {
    @Test fun emptyScopeDoesNotShowRedundantSelector() {
        assertFalse(shouldShowLibraryScopeSelector(0, allSelected = true))
    }

    @Test fun oneLibraryWithoutOverrideDoesNotShowSelector() {
        assertFalse(shouldShowLibraryScopeSelector(1, allSelected = true))
    }

    @Test fun multipleLibrariesAlwaysOfferTheScopeChooser() {
        assertTrue(shouldShowLibraryScopeSelector(2, allSelected = true))
        assertTrue(shouldShowLibraryScopeSelector(3, allSelected = false))
    }

    @Test fun orphanScopeCanAlwaysBeResetToAll() {
        assertTrue(shouldShowLibraryScopeSelector(0, allSelected = false))
        assertTrue(shouldShowLibraryScopeSelector(1, allSelected = false))
    }
}
