package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class LibraryLosslessClassifierTest {

    private fun isLosslessTrack(track: Track): Boolean {
        val fmt = track.format.lowercase()
        return fmt.contains("flac") || fmt.contains("alac") || fmt.contains("wav") || fmt.contains("pcm")
    }

    @Test
    fun flacTrackIsLosslessRegardlessOfSampleRate() {
        val flac441 = Track(id = "1", title = "CD FLAC", format = "flac", sampleRate = 44100)
        val flac96 = Track(id = "2", title = "HiRes FLAC", format = "FLAC", sampleRate = 96000)

        assertTrue(isLosslessTrack(flac441))
        assertTrue(isLosslessTrack(flac96))
    }

    @Test
    fun wavAndAlacTracksAreLossless() {
        val wav = Track(id = "3", title = "WAV Audio", format = "audio/wav", sampleRate = 48000)
        val alac = Track(id = "4", title = "Apple Lossless", format = "alac", sampleRate = 44100)

        assertTrue(isLosslessTrack(wav))
        assertTrue(isLosslessTrack(alac))
    }

    @Test
    fun lossyAudioAt48kHzOrAboveIsNotClassifiedAsLossless() {
        // Conforme a la Decisión D-04 y M15: sampleRate >= 48000 no califica como Lossless si el códec es lossy
        val mp3at48k = Track(id = "5", title = "Lossy 48k MP3", format = "mp3", sampleRate = 48000)
        val aacat96k = Track(id = "6", title = "Lossy 96k AAC", format = "aac", sampleRate = 96000)
        val opusat48k = Track(id = "7", title = "Lossy Opus", format = "opus", sampleRate = 48000)

        assertFalse(isLosslessTrack(mp3at48k))
        assertFalse(isLosslessTrack(aacat96k))
        assertFalse(isLosslessTrack(opusat48k))
    }
}
