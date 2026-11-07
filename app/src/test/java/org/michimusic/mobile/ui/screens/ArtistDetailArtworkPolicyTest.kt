package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource

class ArtistDetailArtworkPolicyTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val other = "aaa436da-b331-4e58-a0fd-3d64d090c120"

    private fun selection(mbid: String, status: ArtworkLoadStatus = ArtworkLoadStatus.READY) =
        ArtistArtworkSelection(
            slot = ArtworkSlot.ARTIST_HERO,
            assetId = "sha256:${"a".repeat(64)}",
            confirmedMbid = mbid,
            localImagePath = "/tmp/hero.png",
            focus = ArtworkFocus(),
            manuallyChosen = false,
            revision = 1L,
            status = status,
            source = ArtworkSource.COMMONS_P18,
        )

    @Test
    fun heroFromAnotherMbidIsNeverUsedAsFallback() {
        assertNull(selectEffectiveHeroArtwork(mbid, fromController = null, fromRoom = selection(other)))
    }

    @Test
    fun controllerSelectionWinsOnlyWhenItMatchesTheProfile() {
        val same = selection(mbid)
        assertEquals(same, selectEffectiveHeroArtwork(mbid, same, selection(other)))
    }

    @Test
    fun nonReadyOrMissingProfileKeepsTheMonogram() {
        assertNull(selectEffectiveHeroArtwork(mbid, selection(mbid, ArtworkLoadStatus.CACHE_ERROR), null))
        assertNull(selectEffectiveHeroArtwork(null, selection(mbid), selection(mbid)))
    }

    @Test
    fun invalidControllerFallsBackToAValidRoomSelection() {
        val persisted = selection(mbid)
        assertEquals(
            persisted,
            selectEffectiveHeroArtwork(
                mbid,
                fromController = selection(mbid, ArtworkLoadStatus.CACHE_ERROR),
                fromRoom = persisted,
            ),
        )
        assertEquals(
            persisted,
            selectEffectiveHeroArtwork(mbid, fromController = selection(other), fromRoom = persisted),
        )
    }

    @Test
    fun bothInvalidCandidatesKeepTheMonogram() {
        assertNull(
            selectEffectiveHeroArtwork(
                mbid,
                fromController = selection(other),
                fromRoom = selection(mbid, ArtworkLoadStatus.CACHE_ERROR),
            ),
        )
    }

    @Test
    fun twoValidCandidatesWithTheSameRevisionKeepTheControllerAsThePrimarySelection() {
        val controller = selection(mbid).copy(assetId = "sha256:${"c".repeat(64)}")
        val room = selection(mbid).copy(assetId = "sha256:${"d".repeat(64)}")
        assertEquals(controller, selectEffectiveHeroArtwork(mbid, controller, room))
    }

    @Test
    fun newerPersistedRoomSelectionWinsOverAStaleControllerSelection() {
        val controller = selection(mbid).copy(
            assetId = "sha256:${"c".repeat(64)}",
            revision = 4L,
        )
        val room = selection(mbid).copy(
            assetId = "sha256:${"d".repeat(64)}",
            revision = 5L,
        )
        assertEquals(room, selectEffectiveHeroArtwork(mbid, controller, room))
        assertEquals(
            controller,
            selectEffectiveHeroArtwork(
                mbid,
                controller,
                room.copy(revision = 4L),
            ),
        )
    }
}
