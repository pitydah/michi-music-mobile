package org.michimusic.mobile.ui.seeker

import org.junit.Assert.*
import org.junit.Test

class WaveformSourcePolicyTest {
    @Test fun onlyLocalContentAndFilesAreEligible() {
        assertNotNull(eligibleWaveformPath("content://media/external/audio/media/12", false, false))
        assertNotNull(eligibleWaveformPath("/storage/emulated/0/Music/a.flac", false, false))
        assertNotNull(eligibleWaveformPath("file:///tmp/a.mp3", false, false))
        for (path in listOf("", "https://other.example/audio.mp3", "./music.mp3", "remote:123", "file://host.example/audio.mp3")) {
            assertNull(eligibleWaveformPath(path, false, false))
        }
    }
    @Test fun cannotCrossRemotePlaybackOrStreamingAuthority() {
        assertNull(eligibleWaveformPath("/storage/music.flac", false, true))
        assertNull(eligibleWaveformPath("content://media/42", true, false))
    }
    @Test fun offlineDecodeHasStrictFrameAndDurationBudget() {
        assertEquals(44_100L * 180L, waveformFrameBudget(180_000_000L, 44_100))
        assertNull(waveformFrameBudget(0, 44_100))
        assertNull(waveformFrameBudget(13L * 60 * 1_000_000, 44_100))
        assertNull(waveformFrameBudget(180_000_000L, 0))
    }
}
