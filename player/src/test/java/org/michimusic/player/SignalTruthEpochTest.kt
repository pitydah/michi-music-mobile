package org.michimusic.player

import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.PcmFormat

class SignalTruthEpochTest {
    @Test fun recomputationDoesNotRefreshObservedSourceOrPcm() {
        val tracker = SignalTruthTracker()
        tracker.onDecoderInputFormat(Format.Builder().setSampleMimeType("audio/flac")
            .setSampleRate(48000).setChannelCount(2).build())
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 48000, channels = 2, bitDepth = 16, codec = "pcm_s16le"))
        val first = tracker.observation.value
        assertTrue(first.sourceObservedAtMs > 0L)
        assertTrue(first.decodedObservedAtMs > 0L)
        tracker.recompute()
        val second = tracker.observation.value
        assertEquals(first.sourceObservedAtMs, second.sourceObservedAtMs)
        assertEquals(first.decodedObservedAtMs, second.decodedObservedAtMs)
        assertEquals(first.observedAtMs, second.observedAtMs)
    }

    @Test fun trackResetInvalidatesOldSamplesAndMovesGeneration() {
        val tracker = SignalTruthTracker()
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 96000, channels = 2, bitDepth = 24, codec = "pcm_s24le"))
        val first = tracker.observation.value
        tracker.onTrackChanged(null)
        val second = tracker.observation.value
        assertTrue(second.trackGeneration > first.trackGeneration)
        assertEquals(0L, second.decodedObservedAtMs)
        assertEquals(null, second.decodedFormat)
    }

    @Test fun gaplessTransitionRetainsLiveAudioTrackButInvalidatesOldSourceAndPcm() {
        val tracker = SignalTruthTracker()
        tracker.onDecoderInputFormat(Format.Builder().setSampleMimeType("audio/flac")
            .setSampleRate(48000).setChannelCount(2).build())
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 48000, channels = 2, bitDepth = 16, codec = "pcm_s16le"))
        tracker.onAudioTrackInitialized(48000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT, generation = 5L)
        val before = tracker.observation.value
        assertTrue(before.outputObservedAtMs > 0L)
        tracker.onMediaItemTransitionPending()
        val after = tracker.observation.value
        assertTrue(after.trackGeneration > before.trackGeneration)
        assertEquals(0L, after.sourceObservedAtMs)
        assertEquals(0L, after.decodedObservedAtMs)
        assertEquals(null, after.decodedFormat)
        assertEquals(before.outputObservedAtMs, after.outputObservedAtMs)
        assertEquals(before.outputConfig, after.outputConfig)
    }

    @Test fun oldAudioTrackReleaseCannotEraseNewerGeneration() {
        val tracker = SignalTruthTracker()
        tracker.onAudioTrackInitialized(44100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT, generation = 3L)
        val newTime = tracker.observation.value.outputObservedAtMs
        tracker.onAudioTrackReleased(generation = 2L)
        assertEquals(newTime, tracker.observation.value.outputObservedAtMs)
        tracker.onAudioTrackReleased(generation = 3L)
        assertEquals(0L, tracker.observation.value.outputObservedAtMs)
    }
}
