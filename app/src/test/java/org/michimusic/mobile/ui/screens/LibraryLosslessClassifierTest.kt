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

    @Test
    fun dsdAndContainerAmbiguitiesAreHandledCorrectly() {
        // DSD is out of scope on Mobile and must not be classified as Lossless
        val dsdTrack = Track(id = "12", title = "DSD Track", format = "dsd", sampleRate = 2822400)
        val dsfTrack = Track(id = "13", title = "DSF Track", format = "audio/dsf", sampleRate = 5644800)
        assertFalse(dsdTrack.isLossless)
        assertEquals(CodecClassification.UNKNOWN, dsdTrack.codecClassification)
        assertFalse(dsfTrack.isLossless)
        assertEquals(CodecClassification.UNKNOWN, dsfTrack.codecClassification)

        // ADPCM is compressed lossy, not linear PCM
        val adpcmTrack = Track(id = "14", title = "ADPCM WAV", format = "audio/wav; codec=adpcm", sampleRate = 44100)
        assertFalse(adpcmTrack.isLossless)
        assertEquals(CodecClassification.LOSSY, adpcmTrack.codecClassification)

        // Bare M4A container without explicit codec is UNKNOWN
        val bareM4a = Track(id = "15", title = "M4A File", format = "m4a", sampleRate = 44100)
        assertFalse(bareM4a.isLossless)
        assertEquals(CodecClassification.UNKNOWN, bareM4a.codecClassification)

        // M4A with explicit ALAC is LOSSLESS; with AAC is LOSSY
        val m4aAlac = Track(id = "16", title = "M4A ALAC", format = "m4a; codec=alac", sampleRate = 44100)
        val m4aAac = Track(id = "17", title = "M4A AAC", format = "m4a; codec=aac", sampleRate = 44100)
        assertTrue(m4aAlac.isLossless)
        assertEquals(CodecClassification.LOSSLESS, m4aAlac.codecClassification)
        assertFalse(m4aAac.isLossless)
        assertEquals(CodecClassification.LOSSY, m4aAac.codecClassification)
    }
}
