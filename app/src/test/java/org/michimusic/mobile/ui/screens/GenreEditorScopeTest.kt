package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ME-11W-GEN-P0 regression: the metadata editor validates the MusicRootAuthority
 * revision. The genre-index generation is a different clock and must never be
 * forwarded as the editor scope generation.
 */
class GenreEditorScopeTest {
    @Test
    fun editorScopeIsTheRootRevisionNotTheGenreGeneration() {
        val genreGeneration = 3L
        val rootRevision = 11L
        val forwarded = genreEditorScopeGeneration(rootRevision)
        assertEquals(rootRevision, forwarded)
        assertNotEquals(genreGeneration, forwarded)
    }
}
