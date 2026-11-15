package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistArtworkAutoBackfillTest {
    @Test fun cursorIsDeterministicAndContainsNoPersonalPath() {
        val a = ArtistArtworkBackfillCursor.key("lib_device_local", "content://private/folder")
        val b = ArtistArtworkBackfillCursor.key("lib_device_local", "content://private/folder")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("^cursor_[0-9a-f]{24}$")))
        assertFalse(a.contains("private"))
    }

    @Test fun cursorIsScopedByLibraryAndSource() {
        assertFalse(ArtistArtworkBackfillCursor.key("lib-a", "saf") ==
            ArtistArtworkBackfillCursor.key("lib-b", "saf"))
        assertFalse(ArtistArtworkBackfillCursor.key("lib-a", "saf-a") ==
            ArtistArtworkBackfillCursor.key("lib-a", "saf-b"))
    }
}
