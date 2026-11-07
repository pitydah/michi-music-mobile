package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.Track

class AlbumQualityPolicyTest {
    @Test fun summaryIsOnlyReportedIfEveryLocalTrackHasSameKnownFormat() {
        val tracks = listOf(
            Track(id = "a", title = "A", format = "audio/flac"),
            Track(id = "b", title = "B", format = "flac"),
        )
        assertEquals("FLAC", albumCodecSummary(tracks))
    }

    @Test fun differentAndIncompleteFormatsDoNotMasqueradeAsOneCodec() {
        assertEquals("Formatos mixtos", albumCodecSummary(listOf(
            Track(id = "a", title = "A", format = "flac"),
            Track(id = "b", title = "B", format = "mp3"),
        )))
        assertEquals("Formatos mixtos", albumCodecSummary(listOf(
            Track(id = "a", title = "A", format = "flac"),
            Track(id = "b", title = "B"),
        )))
        assertNull(albumCodecSummary(emptyList()))
    }

    @Test fun countUsesActualLocalTracks() {
        assertEquals("1 pista local", albumTrackCountLabel(1))
        assertEquals("2 pistas locales", albumTrackCountLabel(2))
        assertEquals("2 pistas de la biblioteca", albumTrackCountLabel(2, allLocal = false))
    }
}
