package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumLayoutPolicyTest {
    @Test fun compactScreenStacksActions() {
        listOf(320, 360, 412, 440).forEach { assertTrue(albumActionsStacked(it, 1.0f)) }
    }
    @Test fun expandedScreenUsesHorizontalActionsOnlyAtNormalFontScale() {
        assertFalse(albumActionsStacked(480, 1.0f))
        assertFalse(albumActionsStacked(600, 1.2f))
        assertTrue(albumActionsStacked(600, 1.3f))
        assertTrue(albumActionsStacked(600, 2.0f))
        assertTrue(albumActionsStacked(600, Float.NaN))
    }
}
