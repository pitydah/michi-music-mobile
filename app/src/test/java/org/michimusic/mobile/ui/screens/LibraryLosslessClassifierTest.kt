package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.CodecClassification
import org.michimusic.core.models.Track

class LibraryLosslessClassifierTest {

    @Test
    fun flacTrackIsLosslessRegardlessOfSampleRate() {
        val flac441 = Track(id = "1", title = "CD FLAC", format = "flac", sampleRate = 44100)
        val flac96 = Track(id = "2", title = "HiRes FLAC", format = "FLAC", sampleRate = 96000)

        assertTrue(flac441.isLossless)
        assertEquals(CodecClassification.LOSSLESS, flac441.codecClassification)
        assertTrue(flac96.isLossless)
        assertEquals(CodecClassification.LOSSLESS, flac96.codecClassification)
    }

    @Test
    fun wavAndAlacTracksAreLossless() {
        val wav = Track(id = "3", title = "WAV Audio", format = "audio/wav", sampleRate = 48000)
        val alac = Track(id = "4", title = "Apple Lossless", format = "alac", sampleRate = 44100)
        val aiff = Track(id = "5", title = "AIFF Audio", format = "aiff", sampleRate = 44100)

        assertTrue(wav.isLossless)
        assertEquals(CodecClassification.LOSSLESS, wav.codecClassification)
        assertTrue(alac.isLossless)
        assertEquals(CodecClassification.LOSSLESS, alac.codecClassification)
        assertTrue(aiff.isLossless)
        assertEquals(CodecClassification.LOSSLESS, aiff.codecClassification)
    }

    @Test
    fun lossyAudioAt48kHzOrAboveIsNotClassifiedAsLossless() {
        // Conforme a la Decisión D-04 y M15: sampleRate >= 48000 no califica como Lossless si el códec es lossy
        val mp3at48k = Track(id = "5", title = "Lossy 48k MP3", format = "mp3", sampleRate = 48000)
        val aacat96k = Track(id = "6", title = "Lossy 96k AAC", format = "aac", sampleRate = 96000)
        val opusat48k = Track(id = "7", title = "Lossy Opus", format = "opus", sampleRate = 48000)
        val oggat48k = Track(id = "8", title = "Lossy Vorbis", format = "ogg", sampleRate = 48000)

        assertFalse(mp3at48k.isLossless)
        assertEquals(CodecClassification.LOSSY, mp3at48k.codecClassification)
        assertFalse(aacat96k.isLossless)
        assertEquals(CodecClassification.LOSSY, aacat96k.codecClassification)
        assertFalse(opusat48k.isLossless)
        assertEquals(CodecClassification.LOSSY, opusat48k.codecClassification)
        assertFalse(oggat48k.isLossless)
        assertEquals(CodecClassification.LOSSY, oggat48k.codecClassification)
    }

    @Test
    fun unknownOrEmptyFormatClassifiedAsUnknownAndNotLossless() {
        val emptyFormat = Track(id = "9", title = "Empty Format", format = "", sampleRate = 44100)
        val unknownFormat = Track(id = "10", title = "Unknown String", format = "unknown", sampleRate = 48000)
        val unrecognizedFormat = Track(id = "11", title = "Binary Blob", format = "application/octet-stream", sampleRate = 0)

        assertFalse(emptyFormat.isLossless)
        assertEquals(CodecClassification.UNKNOWN, emptyFormat.codecClassification)
        assertFalse(unknownFormat.isLossless)
        assertEquals(CodecClassification.UNKNOWN, unknownFormat.codecClassification)
        assertFalse(unrecognizedFormat.isLossless)
        assertEquals(CodecClassification.UNKNOWN, unrecognizedFormat.codecClassification)
    }
}
