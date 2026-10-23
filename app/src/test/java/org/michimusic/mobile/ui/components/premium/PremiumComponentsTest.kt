package org.michimusic.mobile.ui.components.premium

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.theme.MichiSpacing

class PremiumComponentsTest {

    @Test
    fun testMichiPlaybackControlDefaultState() {
        val state = PlaybackControlUiState()
        assertFalse(state.isPlaying)
        assertTrue(state.canSkipPrevious)
        assertTrue(state.canSkipNext)
        assertFalse(state.isShuffleEnabled)
        assertEquals(0, state.repeatMode)
        assertFalse(state.isLoading)
    }

    @Test
    fun testMichiTouchTargetAccessibilityCompliance() {
        assertTrue(
            "Min touch target for premium icon action must be at least 48dp",
            MichiSpacing.minTouchTarget >= 48.dp,
        )
    }

    @Test
    fun testMichiSurfaceLevelHierarchy() {
        val levels = MichiSurfaceLevel.values().toList()
        assertTrue(levels.contains(MichiSurfaceLevel.Canvas))
        assertTrue(levels.contains(MichiSurfaceLevel.Content))
        assertTrue(levels.contains(MichiSurfaceLevel.Raised))
        assertTrue(levels.contains(MichiSurfaceLevel.Floating))
        assertEquals(4, levels.size)
    }
}
