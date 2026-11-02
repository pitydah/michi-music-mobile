package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.SignalFormat
import org.michimusic.core.models.Track

class SignalSourcePresentationTest {
    @Test fun mp3NeverInheritsPcmBitDepthOrLosslessLabel() {
        val source = SignalFormat("audio/mpeg", 0, 44100, 2, 320000, "FILE_AVG")
        val label = SignalSourcePresentation.describe(source, Track("1", "MP3", format = "mpeg"))!!
        assertTrue(label.startsWith("MP3 · 44,1 kHz · 320 kb/s"))
        assertFalse(label.contains("24 bit"))
        assertFalse(label.contains("FLAC"))
    }
    @Test fun flacSourceUsesOnlyObservedFileDepth() {
        assertEquals("FLAC · 96 kHz", SignalSourcePresentation.describe(
            SignalFormat("audio/flac", 0, 96000, 2), Track("2", "FLAC", format="flac")))
        assertEquals("FLAC · 24 bit · 96 kHz", SignalSourcePresentation.describe(
            SignalFormat("audio/flac", 24, 96000, 2), Track("2", "FLAC", format="flac")))
    }
    @Test fun wavPcmRetainsContainerAndBitsFromFileOnly() {
        assertEquals("WAV (PCM) · 16 bit · 44,1 kHz", SignalSourcePresentation.describe(
            SignalFormat("audio/raw", 16, 44100, 2), Track("3", "Wav", format="wav")))
    }
    @Test fun unknownBitrateIsNotFabricatedFromDecodedPcm() {
        assertEquals("MP3 · 44,1 kHz", SignalSourcePresentation.describe(
            SignalFormat("audio/mpeg", 0, 44100, 2), Track("4", "MP3", format="mpeg")))
    }
    @Test fun peakBitrateIsNeverLabeledAsAverage() {
        val label = SignalSourcePresentation.describe(
            SignalFormat("audio/aac", 0, 48000, 2, 256000, "PEAK"), null)!!
        assertTrue(label.contains("256 kb/s (pico)"))
        assertFalse(label.contains("promedio"))
    }
    @Test fun unknownSourceIsUnknownNotFlacByInference() {
        assertNull(SignalSourcePresentation.describe(null, Track("5", "Unknown", format="")))
    }

    @Test fun decodedPcmDoesNotReplaceAnOriginalMp3Codec() {
        val label = SignalSourcePresentation.describe(
            SignalFormat("audio/raw", 24, 96000, 2), Track("6", "MP3", format="mpeg"))!!
        assertTrue(label.startsWith("MP3"))
        assertFalse(label.contains("24 bit"))
    }

    @Test fun genericMp4ContainerDoesNotMaskKnownAacCodec() {
        val label = SignalSourcePresentation.describe(
            SignalFormat("audio/mp4", 0, 44100, 2), Track("7", "AAC", format="aac"))!!
        assertTrue(label.startsWith("AAC"))
    }

    @Test fun adpcmInsideWaveNeverClaimsUncompressedPcm() {
        val label = SignalSourcePresentation.describe(
            SignalFormat("audio/ima-adpcm", 16, 44100, 2), Track("8", "ADPCM", format="wav"))!!
        assertTrue(label.startsWith("ADPCM"))
        assertFalse(label.contains("16 bit"))
        assertFalse(label.contains("(PCM)"))
    }

    @Test fun wavpackWithoutCorrectionProofDoesNotClaimSourceBitDepth() {
        val label = SignalSourcePresentation.describe(
            SignalFormat("audio/x-wavpack", 24, 48000, 2), Track("9", "Hybrid", format="wv"))!!
        assertTrue(label.startsWith("WavPack"))
        assertFalse(label.contains("24 bit"))
    }

}
