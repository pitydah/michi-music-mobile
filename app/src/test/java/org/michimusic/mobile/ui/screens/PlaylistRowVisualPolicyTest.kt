package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRowVisualPolicyTest {
    @Test fun dropTargetHasPriorityOverPlaying() {
        assertEquals(PlaylistRowEmphasis.DROP_TARGET, playlistRowEmphasis(true, true, true))
        assertEquals(PlaylistRowEmphasis.CURRENT_PAUSED, playlistRowEmphasis(true, false, false))
        assertEquals(PlaylistRowEmphasis.CURRENT_PLAYING, playlistRowEmphasis(true, true, false))
        assertEquals(PlaylistRowEmphasis.NONE, playlistRowEmphasis(false, true, false))
    }

    @Test fun emphasisIsVisibleButNotAppliedToIdleRows() {
        assertNull(playlistRowOutline(PlaylistRowEmphasis.NONE, highContrast = false))
        val playing = playlistRowOutline(PlaylistRowEmphasis.CURRENT_PLAYING, false)
        val paused = playlistRowOutline(PlaylistRowEmphasis.CURRENT_PAUSED, false)
        assertNotNull(playing)
        assertNotNull(paused)
        assertTrue(playing!!.tint.alpha > paused!!.tint.alpha)
        assertTrue(playlistRowOutline(PlaylistRowEmphasis.CURRENT_PAUSED, true)!!.widthDp > paused.widthDp)
        assertTrue(playlistRowOutline(PlaylistRowEmphasis.DROP_TARGET, true)!!.widthDp >= 2f)
    }
}
