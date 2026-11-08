package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.Track

class AlbumQualityPolicyTest {
    @Test fun codecUsesVerifiedExtensionAndMimeWithoutGuessingMpeg() {
        assertEquals("MP3", albumCodecLabel(track(extension = "mp3", mime = "audio/mpeg")))
        assertEquals("MP2", albumCodecLabel(track(extension = "mp2", mime = "audio/mpeg")))
        assertEquals("Audio MPEG", albumCodecLabel(track(mime = "audio/mpeg")))
        assertNull(albumCodecLabel(track(extension = "mp3", mime = "audio/flac")))
    }

    @Test fun supportedCodecLabelsAreCentralized() {
        mapOf(
            "flac" to "FLAC",
            "aac" to "AAC",
            "alac" to "ALAC",
            "wav" to "WAV",
            "aiff" to "AIFF",
            "ogg" to "Ogg",
            "opus" to "Opus",
            "wv" to "WavPack",
        ).forEach { (extension, expected) ->
            assertEquals(expected, albumCodecLabel(track(extension = extension)))
        }
    }

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
        assertNull(albumCodecSummary(listOf(Track(id = "x", title = "Unknown"))))
    }

    @Test fun countUsesActualLocalTracks() {
        assertEquals("1 pista local", albumTrackCountLabel(1))
        assertEquals("2 pistas locales", albumTrackCountLabel(2))
        assertEquals("2 pistas de la biblioteca", albumTrackCountLabel(2, allLocal = false))
    }

    private fun track(extension: String = "", mime: String = "") = Track(
        id = "$extension-$mime",
        title = "Test",
        sourceFileExtension = extension,
        sourceMimeType = mime,
    )
}
