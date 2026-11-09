package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.DspPipelineStatus
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.SignalFormat
import org.michimusic.core.models.SignalLayer

/** Regression receipts for fail-closed Mobile evidence. No DAC/transport certification. */
class SignalTruthEvidenceRegressionTest {
    private val local = PlaybackEndpoint.LocalPhone
    private val genericRemote = PlaybackEndpoint(
        id = "generic_output", name = "Other remote renderer",
        type = EndpointType.DESKTOP_PLAYER, isLocal = false,
        isConnected = true, capabilities = setOf("AUDIO_OUTPUT"),
    )
    private val stream = PlaybackEndpoint(
        id = "michi_receiver", name = "Michi Stream",
        type = EndpointType.STREAM_RECEIVER, isLocal = false,
        isConnected = true, capabilities = setOf("AUDIO_OUTPUT"),
    )

    private fun layer(items: List<org.michimusic.core.models.SignalProvenanceItem>, layer: SignalLayer) =
        items.first { it.layer == layer }

    @Test fun genericAudioOutputCapabilityDoesNotBecomeMichiStream() {
        val oldLocal = AudioSignalObservation(
            decodedFormat = SignalFormat("PCM", 24, 96000, 2),
            decodedObservedAtMs = 987L,
            outputConfig = SignalFormat("AudioTrack_PCM", 24, 96000, 2),
            outputObservedAtMs = 987L,
        )
        val output = layer(SignalTruthResolver.resolveProvenance(oldLocal, genericRemote, null, true), SignalLayer.ANDROID_OUTPUT)
        assertEquals(SignalEvidenceType.UNKNOWN, output.evidenceType)
        assertEquals(0L, output.freshnessMs)
    }

    @Test fun sessionSelectedStreamWithGenericEndpointIsConfiguredNotObserved() {
        val oldLocal = AudioSignalObservation(
            decodedFormat = SignalFormat("PCM", 24, 96000, 2), decodedObservedAtMs = 55L)
        val output = layer(SignalTruthResolver.resolveProvenance(
            oldLocal, genericRemote, null, true, streamSelectedBySession = true),
            SignalLayer.ANDROID_OUTPUT)
        assertEquals(SignalEvidenceType.CONFIGURED, output.evidenceType)
        assertEquals(0L, output.freshnessMs)
    }

    @Test fun routeTimestampDoesNotUpgradeUndatedDecoderToObserved() {
        val observation = AudioSignalObservation(
            decodedFormat = SignalFormat("PCM", 24, 48000, 2),
            observedAtMs = 987L,
            routeObservedAtMs = 987L,
        )
        val decoding = layer(SignalTruthResolver.resolveProvenance(observation, local, null, false), SignalLayer.DECODING)
        assertEquals(SignalEvidenceType.UNKNOWN, decoding.evidenceType)
        assertEquals(0L, decoding.freshnessMs)
    }

    @Test fun outputConfigWithoutOwnTimestampIsUnknown() {
        val observation = AudioSignalObservation(
            outputConfig = SignalFormat("AudioTrack_PCM", 16, 44100, 2),
            observedAtMs = 111L,
            decodedObservedAtMs = 111L,
        )
        val output = layer(SignalTruthResolver.resolveProvenance(observation, local, null, false), SignalLayer.ANDROID_OUTPUT)
        assertEquals(SignalEvidenceType.UNKNOWN, output.evidenceType)
    }

    @Test fun streamSelectionIsNotRtpDeliveryEvidence() {
        val observation = AudioSignalObservation(
            decodedFormat = SignalFormat("PCM", 24, 96000, 2),
            decodedObservedAtMs = 300L,
        )
        val output = layer(SignalTruthResolver.resolveProvenance(observation, stream, null, true), SignalLayer.ANDROID_OUTPUT)
        assertEquals(SignalEvidenceType.CONFIGURED, output.evidenceType)
        assertEquals(0L, output.freshnessMs)
        assertTrue(output.confidenceNote.orEmpty().contains("faltan recibos"))
    }

    @Test fun nonFiniteDspValuesNeverBecomeNumbersOnScreen() {
        val observation = AudioSignalObservation(dspStatus = DspPipelineStatus(
            isReplayGainActive = true, replayGainAppliedDb = Float.NaN,
            preampAppliedDb = Float.POSITIVE_INFINITY,
            headphonePeqActive = true, headphoneHeadroomDb = Float.NEGATIVE_INFINITY,
            upstreamSafetyReductionDb = Float.NEGATIVE_INFINITY,
        ))
        val processing = layer(SignalTruthResolver.resolveProvenance(observation, local, null, false), SignalLayer.PROCESSING)
        assertEquals(SignalEvidenceType.CONFIGURED, processing.evidenceType)
        assertTrue(processing.value.contains("no verificada"))
        assertFalse(processing.value.contains("NaN"))
        assertFalse(processing.value.contains("Infinity"))
    }
    @Test fun audioTrackConfiguredFormatDoesNotBecomeDacRate() {
        val observation = AudioSignalObservation(
            outputConfig = SignalFormat("AudioTrack_PCM", 24, 96000, 2),
            outputObservedAtMs = 775L,
        )
        val output = layer(SignalTruthResolver.resolveProvenance(observation, local, null, false),
            SignalLayer.ANDROID_OUTPUT)
        assertEquals(SignalEvidenceType.OBSERVED, output.evidenceType)
        assertTrue(output.value.contains("96000 Hz"))
        assertTrue(output.value.contains("no del DAC"))
    }
}
