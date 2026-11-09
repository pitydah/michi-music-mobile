package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.DspPipelineStatus
import org.michimusic.core.models.SignalFormat

class SignalTruthOwnerPolicyTest {
    private val format = SignalFormat("PCM", 24, 96000, 2)

    @Test fun matchingOwnerPreservesTrackedPcm() {
        val original = AudioSignalObservation(trackIdentity = "42", decodedFormat = format,
            outputConfig = format, decodedObservedAtMs = 10L, outputObservedAtMs = 11L)
        assertEquals(original, SignalTruthOwnerPolicy.forVisibleTrack(original, "42", true))
    }

    @Test fun differentOwnerInvalidatesLocalDecoderAndSinkButNotRouteMetadata() {
        val previous = AudioSignalObservation(trackIdentity = "41", decodedFormat = format,
            sourceFormat = format, outputConfig = format, dspStatus = DspPipelineStatus(isEqActive = true),
            routeStatus = "RUTA CONFIRMADA", decodedObservedAtMs = 123L,
            outputObservedAtMs = 124L, routeObservedAtMs = 125L)
        val guarded = SignalTruthOwnerPolicy.forVisibleTrack(previous, "42", true)
        assertNull(guarded.decodedFormat)
        assertNull(guarded.sourceFormat)
        assertNull(guarded.outputConfig)
        assertFalse(guarded.dspStatus.isEqActive)
        assertEquals(0L, guarded.decodedObservedAtMs)
        assertEquals(0L, guarded.outputObservedAtMs)
        assertEquals("RUTA CONFIRMADA", guarded.routeStatus)
        assertEquals(125L, guarded.routeObservedAtMs)
    }

    @Test fun missingOwnerCannotProveCurrentLocalPcm() {
        assertFalse(SignalTruthOwnerPolicy.isVisibleTrackCurrent(
            AudioSignalObservation(decodedFormat = format), "42"))
        assertNull(SignalTruthOwnerPolicy.forVisibleTrack(
            AudioSignalObservation(decodedFormat = format), "42", true).decodedFormat)
    }

    @Test fun remoteHostIsNeverSanitizedAsLocalPlayback() {
        val remote = AudioSignalObservation(trackIdentity = "remote", decodedFormat = format)
        assertEquals(remote, SignalTruthOwnerPolicy.forVisibleTrack(remote, "phone", false))
    }

    @Test fun currentOwnerPermitsFiniteTechnicalEvidence() {
        val current = AudioSignalObservation(trackIdentity = "abc", decodedFormat = format)
        assertTrue(SignalTruthOwnerPolicy.isVisibleTrackCurrent(current, "abc"))
    }
}
