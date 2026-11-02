package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistCoverPolicyTest {

    @Test
    fun `an empty list keeps the editorial placeholder`() {
        assertEquals(PlaylistCoverLayout.Placeholder, playlistCoverLayout(0))
        assertEquals(PlaylistCoverLayout.Placeholder, playlistCoverLayout(-1))
    }

    @Test
    fun `one cover fills the whole tile instead of a one-cell mosaic`() {
        assertEquals(PlaylistCoverLayout.Single, playlistCoverLayout(1))
    }

    @Test
    fun `two or more covers become a mosaic`() {
        assertEquals(PlaylistCoverLayout.Mosaic, playlistCoverLayout(2))
        assertEquals(PlaylistCoverLayout.Mosaic, playlistCoverLayout(3))
        assertEquals(PlaylistCoverLayout.Mosaic, playlistCoverLayout(4))
    }

    @Test
    fun `covers are trimmed deduplicated and capped at four quadrants`() {
        val covers = listOf(" 7 ", "7", "", "  ", "9", "11", "13")

        assertEquals(listOf("7", "9", "11", "13"), playlistCoverIds(covers))
    }

    @Test
    fun `a mosaic never asks for more quadrants than it can paint`() {
        val covers = playlistCoverIds(List(10) { it.toString() })

        assertEquals(4, covers.size)
        assertEquals(PlaylistCoverLayout.Mosaic, playlistCoverLayout(covers.size))
    }
}
