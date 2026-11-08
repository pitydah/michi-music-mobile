package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

class AlbumTrackPositionPolicyTest {
    @Test fun ordinaryMediaStoreNumbersStayOrdinary() {
        listOf(1, 10, 99, 100).forEach { raw ->
            val position = decodeAlbumTrackPosition(track("$raw", raw, mediaStore = true))
            assertEquals(1, position?.discNumber)
            assertEquals(raw, position?.trackNumber)
        }
    }

    @Test fun encodedMediaStoreNumbersDecodeDiscAndTrack() {
        assertEquals(AlbumTrackPosition(1, 6), decodeAlbumTrackPosition(track("a", 1006, true)))
        assertEquals(AlbumTrackPosition(2, 2), decodeAlbumTrackPosition(track("b", 2002, true)))
    }

    @Test fun absentAndMalformedMediaStoreNumbersMakeNoClaim() {
        listOf(0, -1, 1000, 100_001).forEach { raw ->
            assertNull(decodeAlbumTrackPosition(track("$raw", raw, mediaStore = true)))
        }
    }

    @Test fun nonMediaStoreValuesAreNeverDecoded() {
        listOf(TrackSource.LOCAL, TrackSource.SYNCED, TrackSource.STREAMING).forEach { source ->
            val position = decodeAlbumTrackPosition(track(source.name, 1006, mediaStore = false, source = source))
            assertEquals(AlbumTrackPosition(null, 1006), position)
        }
    }

    @Test fun presentationSortsByDiscAndTrackWithStableTiesAndUnknownLast() {
        val tracks = listOf(
            track("unknown", 0, true),
            track("disc2", 2002, true),
            track("tie-a", 1006, true),
            track("tie-b", 1006, true),
            track("first", 1, true),
        )
        val presentation = albumTrackPresentation(tracks)

        assertEquals(listOf("first", "tie-a", "tie-b", "disc2", "unknown"), presentation.tracks.map { it.track.id })
        assertTrue(presentation.showDiscHeaders)
        assertEquals(listOf(1, 1, 1, 2, null), presentation.tracks.map { it.position?.discNumber })
    }

    @Test fun singleDiscDoesNotEmitDiscHeadings() {
        val presentation = albumTrackPresentation(listOf(track("a", 1, true), track("b", 2, true)))
        assertFalse(presentation.showDiscHeaders)
    }

    @Test fun canonicalTracksAndPlayableSubsetShareThePresentedOrder() {
        val tracks = listOf(
            track("disc2", 2002, true),
            track("disc1-track6", 1006, true),
            track("disc1-track1", 1, true),
        )

        assertEquals(
            listOf("disc1-track1", "disc1-track6", "disc2"),
            canonicalAlbumTracks(tracks).map { it.id },
        )
        assertEquals(
            listOf("disc1-track1", "disc2"),
            canonicalPlayableAlbumTracks(tracks, setOf("disc2", "disc1-track1")).map { it.id },
        )
    }

    private fun track(
        id: String,
        raw: Int,
        mediaStore: Boolean,
        source: TrackSource = TrackSource.LOCAL,
    ) = Track(
        id = id,
        title = id,
        trackNumber = raw,
        trackNumberFromMediaStore = mediaStore,
        source = source,
    )
}
