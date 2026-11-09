package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.PcmFormat
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/** Targeted lifecycle negatives for the existing Media3 tracker authority. */
class SignalTruthLifecycleRegressionTest {
    @Test fun directTrackChangeCannotRetainPreviousDecodedPcm() {
        val tracker = SignalTruthTracker()
        tracker.onDecoderInputFormat(Format.Builder().setSampleMimeType("audio/flac")
            .setSampleRate(96000).setChannelCount(2).build())
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 96000, channels = 2, bitDepth = 24, codec = "pcm_s24le"))
        tracker.onAudioTrackInitialized(96000, 2, C.ENCODING_PCM_16BIT, generation = 5L)
        val before = tracker.observation.value
        assertNotNull(before.decodedFormat)
        assertNotNull(before.outputConfig)
        tracker.onTrackChanged(Track(id = "next", title = "Next", artist = "Artist", source = TrackSource.LOCAL))
        val after = tracker.observation.value
        assertTrue(after.trackGeneration > before.trackGeneration)
        assertNull(after.sourceFormat)
        assertNull(after.decodedFormat)
        assertEquals(0L, after.decodedObservedAtMs)
        assertEquals(before.outputConfig, after.outputConfig)
        assertEquals(before.outputObservedAtMs, after.outputObservedAtMs)
    }

    @Test fun invalidDecodedPcmDoesNotPublishObservedTimestamp() {
        val tracker = SignalTruthTracker()
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 0, channels = 2, bitDepth = 24, codec = "pcm"))
        assertNull(tracker.observation.value.decodedFormat)
        assertEquals(0L, tracker.observation.value.decodedObservedAtMs)
        tracker.onPcmFormatDecoded(PcmFormat(sampleRate = 48000, channels = 0, bitDepth = 24, codec = "pcm"))
        assertNull(tracker.observation.value.decodedFormat)
    }

    @Test fun invalidAudioTrackEncodingCannotProduceObservedOutput() {
        val tracker = SignalTruthTracker()
        tracker.onAudioTrackInitialized(48000, 2, -1, generation = 10L)
        assertNull(tracker.observation.value.outputConfig)
        assertEquals(0L, tracker.observation.value.outputObservedAtMs)
        tracker.onAudioTrackInitialized(48000, 2, C.ENCODING_PCM_16BIT, generation = 11L)
        assertNotNull(tracker.observation.value.outputConfig)
        tracker.onAudioTrackReleased(generation = 10L)
        assertNotNull(tracker.observation.value.outputConfig)
    }

    @Test fun mediaTransitionPublishesOwnerBeforeAsynchronousMetadata() {
        val tracker = SignalTruthTracker()
        tracker.onMediaItemTransitionPending("42")
        assertEquals("42", tracker.observation.value.trackIdentity)
        // The service normally guards metadata with transitionEpoch; the tracker
        // also refuses a different track once the new owner is known.
        tracker.onTrackFileMetadata(Track(id = "old", title = "Old", artist = "A",
            source = TrackSource.LOCAL), null)
        assertEquals("42", tracker.observation.value.trackIdentity)
        tracker.onTrackFileMetadata(Track(id = "42", title = "Current", artist = "A",
            source = TrackSource.LOCAL), null)
        assertEquals("42", tracker.observation.value.trackIdentity)
        tracker.onMediaItemTransitionPending()
        assertNull(tracker.observation.value.trackIdentity)
    }
}
