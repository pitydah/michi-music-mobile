package org.michimusic.mobile.ui.screens

import org.junit.Assert.*
import org.junit.Test

class PlaylistImagePolicyTest {
    @Test fun acceptsOnlyRealBoundedImages() {
        assertTrue(acceptablePlaylistImageMetadata("image/jpeg", 1_000_000))
        assertTrue(acceptablePlaylistImageMetadata("image/png", null))
        assertFalse(acceptablePlaylistImageMetadata("image/jpeg", -1))
        assertFalse(acceptablePlaylistImageMetadata("image/heic", 26L * 1024 * 1024))
        assertFalse(acceptablePlaylistImageMetadata("text/html", 10))
        assertFalse(acceptablePlaylistImageMetadata("image/svg+xml", 100))
        assertFalse(acceptablePlaylistImageMetadata(null, null))
    }
}
