package org.michimusic.mobile.artist.artwork

import org.michimusic.data.artist.ArtistArtworkAutoOutboxRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistArtworkAutoContextPolicyTest {
    private fun event(lib: String = "lib", credit: String = "artist",
        association: String = "assoc", album: String = "album", root: String = "saf",
        state: String = "PENDING") = ArtistArtworkAutoOutboxRecord(
            lib, credit, association, album, root, state, updatedAtMs = 10L)
    private fun pending(vararg rows: ArtistArtworkAutoOutboxRecord) =
        ArtistArtworkAutoContextPolicy.needsConfirmation("lib", "artist", "assoc", "saf", rows.toList())

    @Test fun exactBindingValidatedByWorkerDoesNotRequireAnotherAlbumConfirmation() {
        // A different visible album may be unbound, but the exact requested album
        // was confirmed against Room before being put into these validated rows.
        assertEquals(false, pending(event()))
    }
    @Test fun noValidatedAlbumCannotAuthorizeArtwork() {
        assertEquals(true, pending())
    }
    @Test fun otherRootLibraryHomonymOrAlbumIsNotThisContext() {
        assertEquals(true, pending(event(root = "different")))
        assertEquals(true, pending(event(lib = "other")))
        assertEquals(true, pending(event(association = "other-homonym")))
        assertEquals(true, pending(event(credit = "other-credit")))
        assertEquals(true, pending(event(album = "")))
    }
    @Test fun aDeferredOrFinishedRowIsNotAnActiveVerifiedEvent() {
        assertEquals(true, pending(event(state = "READY")))
        assertEquals(true, pending(event(state = "STALE_SOURCE")))
    }
    @Test fun oneUnsafeRowInvalidatesEntireAttemptEvenWithAnotherValidRow() {
        assertEquals(true, pending(event(), event(root = "old-root")))
    }
}
