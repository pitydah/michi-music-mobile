package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGenreIdentityPolicyTest {
    @Test fun matchingMetadataAllowsLegacyRetention() {
        assertTrue(sameLegacySyncTrack("Song", "Band", "Album", 123,
            "Song", "Band", "Album", 123))
    }

    @Test fun cosmeticCasingDoesNotDiscardExistingGenre() {
        assertTrue(sameLegacySyncTrack("  SONG  ", "Band", "Album", 123,
            "song", "band", " album ", 123))
    }

    @Test fun reusedTrackIdWithNewTitleOrAlbumCannotInheritGenre() {
        assertFalse(sameLegacySyncTrack("Song", "Band", "Album", 123,
            "Other", "Band", "Album", 123))
        assertFalse(sameLegacySyncTrack("Song", "Band", "Album", 123,
            "Song", "Band", "New Album", 123))
    }

    @Test fun mismatchedDurationOrUnknownTitleCannotInheritGenre() {
        assertFalse(sameLegacySyncTrack("Song", "Band", "Album", 123,
            "Song", "Band", "Album", 333))
        assertFalse(sameLegacySyncTrack("", "Band", "Album", 123,
            "", "Band", "Album", 123))
    }
}
