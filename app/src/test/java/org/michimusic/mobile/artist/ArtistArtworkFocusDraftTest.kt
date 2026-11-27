package org.michimusic.mobile.artist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtworkSlot

/** D14: no background writes; only a current, changed draft is commit-eligible. */
class ArtistArtworkFocusDraftTest {
    private fun saved() = ArtistArtworkPickerSavedSlotUi(
        slot = ArtworkSlot.ARTIST_THUMB, label = "Mosaico", imagePath = "/private/one.webp",
        focusX = 0.5f, focusY = 0.5f, attribution = null, licenseId = null,
        sourcePageUrl = null, assetId = "sha256:one", revision = 41L,
    )

    @Test fun openingAndCancellingAreReadOnlyDraftStates() {
        val stored = saved()
        val draft = newArtworkFocusDraft(stored)
        assertFalse(mayCommitArtworkFocus(draft, stored, busy = false))
        assertTrue(stored.focusX == 0.5f && stored.revision == 41L)
    }

    @Test fun onlyExactlyMatchingChangedDraftCanCommit() {
        val stored = saved()
        val edited = newArtworkFocusDraft(stored).copy(x = 0.72f, y = 0.11f)
        assertTrue(mayCommitArtworkFocus(edited, stored, busy = false))
        assertFalse(mayCommitArtworkFocus(edited, stored.copy(assetId = "sha256:other"), busy = false))
        assertFalse(mayCommitArtworkFocus(edited, stored.copy(revision = 42L), busy = false))
        assertFalse(mayCommitArtworkFocus(edited, stored.copy(imagePath = "/private/other.webp"), busy = false))
        assertFalse(mayCommitArtworkFocus(edited, stored.copy(slot = ArtworkSlot.ARTIST_HERO), busy = false))
    }

    @Test fun busyNonFiniteOrOutOfRangeCannotWrite() {
        val stored = saved()
        val edited = newArtworkFocusDraft(stored).copy(x = 0.7f)
        assertFalse(mayCommitArtworkFocus(edited, stored, busy = true))
        assertFalse(mayCommitArtworkFocus(edited.copy(x = Float.NaN), stored, busy = false))
        assertFalse(mayCommitArtworkFocus(edited.copy(y = Float.POSITIVE_INFINITY), stored, busy = false))
        assertFalse(mayCommitArtworkFocus(edited.copy(x = -0.1f), stored, busy = false))
        assertFalse(mayCommitArtworkFocus(edited.copy(y = 1.1f), stored, busy = false))
    }

    @Test fun replacingManualPhotoRequiresSeparateAcknowledgement() {
        val candidate = "openverse:candidate-8"
        val keys = setOf(candidate)
        val destination = ArtistArtworkPickerDestination.MOSAIC
        assertFalse(mayCommitArtworkPreview(candidate, keys, destination, true, false, false, false,
            replacementRequired = true, replacementAcknowledged = false))
        assertTrue(mayCommitArtworkPreview(candidate, keys, destination, true, false, false, false,
            replacementRequired = true, replacementAcknowledged = true))
        assertTrue(mayCommitArtworkPreview(candidate, keys, destination, true, false, false, false,
            replacementRequired = false, replacementAcknowledged = false))
    }
}
