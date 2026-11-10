package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.LibraryExclusionEntity

class LibraryVisibilityPolicyTest {
    private fun song(id: String = "local_123", size: Long = 44100L) = Track(
        id = id, title = "Tema", artist = "Artista", album = "Álbum",
        duration = 120_000L, size = size,
        filepath = "content://media/external_primary/audio/media/123",
    )

    @Test fun matchingSourceAndFingerprintIsExcluded() {
        val track = song()
        val entity = LibraryExclusionEntity("mediastore:external_primary:Music", track.id,
            libraryVisibilityFingerprint(track), track.title, track.artist, track.album)
        assertTrue(isLibraryTrackHidden(entity, entity.sourceId, track))
        assertFalse(isLibraryTrackHidden(entity, "different-library", track))
        assertFalse(isLibraryTrackHidden(entity, entity.sourceId, song(size = 999L)))
        assertFalse(isLibraryTrackHidden(entity, entity.sourceId, song(id = "local_124")))
        // Changing metadata without replacing the audio must not spontaneously re-show it.
        assertTrue(isLibraryTrackHidden(entity, entity.sourceId, track.copy(title = "Nuevo título")))
    }
}
