package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** D15: a missing slot and a versioned old slot are different authorisation receipts. */
class ArtistManualArtworkSnapshotTest {
    @Test fun absentSlotIsExplicit() {
        val snapshot = ArtistManualArtworkSnapshot.from(null)
        assertNull(snapshot.assetId)
        assertNull(snapshot.revision)
    }

    @Test fun existingSlotCapturesAssetAndRevisionTogether() {
        val saved = ArtistArtworkSelection(
            slot = ArtworkSlot.ARTIST_THUMB,
            assetId = "sha256:0123", confirmedMbid = "artist-mbid", localImagePath = null,
            focus = ArtworkFocus(), manuallyChosen = true, revision = 13,
        )
        assertEquals(ArtistManualArtworkSnapshot("sha256:0123", 13),
            ArtistManualArtworkSnapshot.from(saved))
        assertThrows(IllegalArgumentException::class.java) {
            ArtistManualArtworkSnapshot("sha256:0123", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArtistManualArtworkSnapshot(null, 13)
        }
    }
}
