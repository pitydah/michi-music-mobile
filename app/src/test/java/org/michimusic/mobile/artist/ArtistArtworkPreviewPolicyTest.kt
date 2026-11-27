package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtworkSlot

/** S3-C1: no writes through gallery taps or cross-candidate reuse of consent. */
class ArtistArtworkPreviewPolicyTest {
    private val keys = setOf("commons:one", "openverse:two")
    private fun permitted(
        key: String? = "commons:one",
        target: ArtistArtworkPickerDestination? = ArtistArtworkPickerDestination.MOSAIC,
        confirmed: Boolean = true,
        busy: Boolean = false,
        searching: Boolean = false,
        consent: Boolean = false,
    ) = mayCommitArtworkPreview(key, keys, target, confirmed, busy, searching, consent)

    @Test fun previewMustHaveExactCandidateDestinationAndFreshConfirmation() {
        assertTrue(permitted())
        assertFalse(permitted(key = "commons:deleted"))
        assertFalse(permitted(key = null))
        assertFalse(permitted(target = null))
        assertFalse(permitted(confirmed = false))
    }

    @Test fun activeOperationsOrSearchDisclosureBlockSaving() {
        assertFalse(permitted(busy = true))
        assertFalse(permitted(searching = true))
        assertFalse(permitted(consent = true))
    }

    @Test fun destinationsNeverImplyAnAdditionalSlot() {
        assertEquals(listOf(ArtworkSlot.ARTIST_THUMB),
            ArtistArtworkPickerDestination.MOSAIC.slots)
        assertEquals(listOf(ArtworkSlot.ARTIST_HERO),
            ArtistArtworkPickerDestination.CARD.slots)
        assertEquals(listOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO),
            ArtistArtworkPickerDestination.BOTH.slots)
    }
}
