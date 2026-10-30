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

    @Test
    fun testMichiTypographyInterFontHierarchy() {
        val inter = org.michimusic.mobile.ui.theme.InterFontFamily
        assertEquals(inter, org.michimusic.mobile.ui.theme.MichiTypography.screenTitle.fontFamily)
        assertEquals(inter, org.michimusic.mobile.ui.theme.MichiTypography.sectionTitle.fontFamily)
        assertEquals(inter, org.michimusic.mobile.ui.theme.MichiTypography.cardTitle.fontFamily)
        // Verify Inter is an authentic FontFamily backed by font resource declarations, not system SansSerif
        assertTrue("InterFontFamily must be a composite FontFamily with authentic font declarations", inter is androidx.compose.ui.text.font.FontListFontFamily)
        val fontList = (inter as androidx.compose.ui.text.font.FontListFontFamily).fonts
        assertEquals(5, fontList.size)
    }

    @Test
    fun testPlaybackControlDynamicSemantics() {
        // Default state
        val defaultState = PlaybackControlUiState()
        assertEquals("Activar modo aleatorio", defaultState.shuffleContentDescription())
        assertEquals("Activar repetición", defaultState.repeatContentDescription())
        assertEquals("Reproducir", defaultState.playPauseContentDescription())

        // Playing state
        val playingState = defaultState.copy(isPlaying = true)
        assertEquals("Pausar", playingState.playPauseContentDescription())

        // Loading state takes precedence
        val loadingState = playingState.copy(isLoading = true)
        assertEquals("Cargando audio", loadingState.playPauseContentDescription())

        // Shuffle toggled
        val shuffleOnState = defaultState.copy(isShuffleEnabled = true)
        assertEquals("Desactivar modo aleatorio", shuffleOnState.shuffleContentDescription())

        // Repeat mode transitions: 0 (OFF) -> 1 (ONE) -> 2 (ALL) -> 0
        val repeatOneState = defaultState.copy(repeatMode = 1)
        assertEquals("Repetición: una pista", repeatOneState.repeatContentDescription())

        val repeatAllState = defaultState.copy(repeatMode = 2)
        assertEquals("Repetición: todo", repeatAllState.repeatContentDescription())
    }
}
