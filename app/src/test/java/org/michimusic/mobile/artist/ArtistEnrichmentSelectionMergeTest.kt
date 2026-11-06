package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource

class ArtistEnrichmentSelectionMergeTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"

    private fun selection(slot: ArtworkSlot, asset: String, mbid: String = this.mbid) =
        ArtistArtworkSelection(
            slot = slot,
            assetId = asset,
            confirmedMbid = mbid,
            localImagePath = "/tmp/$asset.png",
            focus = ArtworkFocus(),
            manuallyChosen = false,
            revision = 1L,
            status = ArtworkLoadStatus.READY,
            source = ArtworkSource.COMMONS_P18,
        )

    @Test
    fun emptyFailureResultKeepsVerifiedSelections() {
        val current = mapOf(ArtworkSlot.ARTIST_THUMB to selection(ArtworkSlot.ARTIST_THUMB, "thumb"))
        assertEquals(current, mergeEnrichmentSelections(current, emptyMap(), identityChanged = false))
    }

    @Test
    fun incomingSlotReplacesOnlyThatSlot() {
        val thumb = selection(ArtworkSlot.ARTIST_THUMB, "thumb")
        val oldHero = selection(ArtworkSlot.ARTIST_HERO, "old-hero")
        val newHero = selection(ArtworkSlot.ARTIST_HERO, "new-hero")
        val merged = mergeEnrichmentSelections(
            current = mapOf(ArtworkSlot.ARTIST_THUMB to thumb, ArtworkSlot.ARTIST_HERO to oldHero),
            incoming = mapOf(ArtworkSlot.ARTIST_HERO to newHero),
            identityChanged = false,
        )
        assertEquals(thumb, merged[ArtworkSlot.ARTIST_THUMB])
        assertEquals(newHero, merged[ArtworkSlot.ARTIST_HERO])
    }

    @Test
    fun identityChangeNeverKeepsThePreviousArtistSelection() {
        val current = mapOf(ArtworkSlot.ARTIST_HERO to selection(ArtworkSlot.ARTIST_HERO, "old"))
        assertTrue(mergeEnrichmentSelections(current, emptyMap(), identityChanged = true).isEmpty())
    }
}
