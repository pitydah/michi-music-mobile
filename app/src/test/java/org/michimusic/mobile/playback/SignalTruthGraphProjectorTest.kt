package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.DspPipelineStatus
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.SignalLayer
import org.michimusic.core.models.SignalProvenanceItem

class SignalTruthGraphProjectorTest {
    private val legacy = listOf(
        SignalProvenanceItem(SignalLayer.SOURCE, "Fuente", "FLAC 24/96", SignalEvidenceType.DECLARED, "local"),
        SignalProvenanceItem(SignalLayer.DECODING, "PCM", "PCM 24/96", SignalEvidenceType.OBSERVED, "local", 100L),
        SignalProvenanceItem(SignalLayer.PROCESSING, "DSP", "Configurado", SignalEvidenceType.CONFIGURED, "local"),
        SignalProvenanceItem(SignalLayer.ANDROID_OUTPUT, "Android", "AudioTrack", SignalEvidenceType.OBSERVED, "local", 120L),
        SignalProvenanceItem(SignalLayer.PHYSICAL_DEVICE, "DAC", "No verificado", SignalEvidenceType.UNKNOWN, "device"),
    )
    private fun graph(
        destination: SignalDestination = SignalDestination.LOCAL,
        dsp: DspPipelineStatus = DspPipelineStatus(),
        routeObserved: Boolean = false,
        usb: Boolean = false,
    ) = SignalTruthGraphProjector.project(legacy, AudioSignalObservation(dspStatus = dsp),
        destination, "endpoint/track/epoch", "DAC USB", routeObserved, usb)

    @Test fun localWithoutDspOmitsInactiveTransformations() {
        val g = graph()
        assertEquals(listOf("source", "decode", "audio-track", "route", "physical"),
            g.nodes.map { it.stableId.substringAfter(':') })
        assertTrue(g.nodes.none { it.stableId.endsWith(":eq") })
    }
    @Test fun localDspAddsSeparateOrderedStagesWithoutPromotingEvidence() {
        val g = graph(dsp = DspPipelineStatus(
            isEqActive = true, isReplayGainActive = true, replayGainAppliedDb = -5.2f,
            headphonePeqActive = true))
        val ids = g.nodes.map { it.stableId }
        assertTrue(ids.indexOf("main:replaygain") < ids.indexOf("main:peq"))
        assertTrue(ids.indexOf("main:peq") < ids.indexOf("main:audio-track"))
        assertTrue(ids.indexOf("main:audio-track") < ids.indexOf("main:eq"))
        assertEquals(SignalEvidenceType.CONFIGURED, g.nodes.first { it.stableId == "main:eq" }.evidenceType)
    }
    @Test fun streamBranchesAndNeverAttributesSessionEqOrPeqToRtp() {
        val g = graph(SignalDestination.MICHI_STREAM,
            DspPipelineStatus(isEqActive = true, headphonePeqActive = true, isReplayGainActive = true))
        assertTrue(g.nodes.any { it.stableId == "rtp:rtp" })
        assertTrue(g.nodes.any { it.stableId == "local:local-monitor" })
        assertTrue(g.edges.any { it.branchId == "local" })
        assertTrue(g.edges.any { it.branchId == "rtp" })
        assertFalse(g.nodes.any { it.stableId.endsWith(":eq") || it.stableId.endsWith(":peq") })
        assertEquals(SignalEvidenceType.UNKNOWN,
            g.nodes.first { it.stableId == "rtp:physical" }.evidenceType)
    }
    @Test fun remoteDoesNotBorrowLocalPcmOrDsp() {
        val g = graph(SignalDestination.REMOTE_HOST,
            DspPipelineStatus(isEqActive = true, isReplayGainActive = true, headphonePeqActive = true))
        assertEquals(listOf("main:source", "main:remote-processing", "main:remote-device"),
            g.nodes.map { it.stableId })
        assertTrue(g.nodes.drop(1).all { it.evidenceType == SignalEvidenceType.UNKNOWN })
    }
    @Test fun usbControlRequiresObservedRoute() {
        assertEquals(SignalStageAction.NONE, graph(usb = true).nodes.first { it.stableId == "main:route" }.action)
        assertEquals(SignalStageAction.USB_MIXER,
            graph(routeObserved = true, usb = true).nodes.first { it.stableId == "main:route" }.action)
    }
    @Test fun nonFiniteGainIsNotPrintedAsNaN() {
        val g = graph(dsp = DspPipelineStatus(isReplayGainActive = true, replayGainAppliedDb = Float.NaN,
            preampAppliedDb = Float.POSITIVE_INFINITY))
        assertFalse(g.nodes.any { it.summary.contains("NaN") || it.summary.contains("Infinity") })
        assertFalse(g.nodes.any { it.stableId.endsWith(":preamp") })
    }
    @Test fun stableIdsSurviveValueChangesButOwnerKeyCanInvalidateExpansion() {
        val a = graph(dsp = DspPipelineStatus(isEqActive = true))
        val b = SignalTruthGraphProjector.project(legacy, AudioSignalObservation(dspStatus =
            DspPipelineStatus(isEqActive = true)), SignalDestination.LOCAL, "different-track")
        assertEquals(a.nodes.map { it.stableId }, b.nodes.map { it.stableId })
        assertTrue(a.ownerKey != b.ownerKey)
    }
    @Test fun replayGainControlOnlyLocalAndNeverPromotesRtp() {
        val dsp = DspPipelineStatus(isReplayGainActive = true, replayGainAppliedDb = -4f)
        val local = graph(dsp = dsp).nodes.first { it.stableId == "main:replaygain" }
        assertEquals(SignalStageAction.REPLAY_GAIN, local.action)
        assertEquals(SignalEvidenceType.CONFIGURED, local.evidenceType)
        val rtp = graph(destination = SignalDestination.MICHI_STREAM, dsp = dsp)
        assertEquals(SignalStageAction.NONE,
            rtp.nodes.first { it.stableId == "main:replaygain" }.action)
    }

    @Test fun peqControlLivesInItsOwnLocalStageOnly() {
        val peq = graph(dsp = DspPipelineStatus(headphonePeqActive = true))
        assertEquals(SignalStageAction.HEADPHONE_PROFILE,
            peq.nodes.first { it.stableId == "main:peq" }.action)
        assertEquals(SignalEvidenceType.CONFIGURED,
            peq.nodes.first { it.stableId == "main:peq" }.evidenceType)
        assertFalse(graph().nodes.any { it.action == SignalStageAction.HEADPHONE_PROFILE })
        assertFalse(graph(destination = SignalDestination.MICHI_STREAM,
            dsp = DspPipelineStatus(headphonePeqActive = true)).nodes.any {
                it.action == SignalStageAction.HEADPHONE_PROFILE
            })
    }

}
