package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.AudioSignalObservation
import org.michimusic.core.models.DspPipelineStatus
import org.michimusic.core.models.SignalEvidenceType
import org.michimusic.core.models.SignalLayer
import org.michimusic.core.models.SignalProvenanceItem

class SignalTruthGraphRenderEvidenceTest {
    private fun layers() = SignalLayer.values().map { layer ->
        SignalProvenanceItem(layer, layer.name, "No observado", SignalEvidenceType.UNKNOWN,
            "local_phone")
    }
    private fun graph(observed: Boolean, frames: Long, pending: Int) =
        SignalTruthGraphProjector.project(
            provenance = layers(),
            observation = AudioSignalObservation(dspStatus = DspPipelineStatus(
                isReplayGainActive = true, replayGainAppliedDb = -6f,
                replayGainPcmObserved = observed, replayGainFramesProcessed = frames,
                replayGainRampPendingFrames = pending,
            )),
            destination = SignalDestination.LOCAL, ownerKey = "track-1/epoch-1",
        )

    @Test fun selectedModeWithoutFramesIsConfiguredNotObserved() {
        val node = graph(false, 0, 0).nodes.single { it.stableId == "main:replaygain" }
        assertEquals(SignalEvidenceType.CONFIGURED, node.evidenceType)
        assertTrue(node.summary.contains("sin recibo PCM"))
    }
    @Test fun firstBufferInFadeCannotAdvertiseCompletedGain() {
        val node = graph(true, 240, 240).nodes.single { it.stableId == "main:replaygain" }
        assertEquals(SignalEvidenceType.CONFIGURED, node.evidenceType)
        assertTrue(node.summary.contains("transición"))
    }
    @Test fun settledCurrentRevisionCanAdvertiseLocalPcmObserved() {
        val node = graph(true, 480, 0).nodes.single { it.stableId == "main:replaygain" }
        assertEquals(SignalEvidenceType.OBSERVED, node.evidenceType)
        assertTrue(node.summary.contains("PCM procesado"))
        assertTrue(node.note!!.contains("no confirma AudioTrack"))
    }
    @Test fun observationFlagWithoutFramesFailsClosed() {
        val node = graph(true, 0, 0).nodes.single { it.stableId == "main:replaygain" }
        assertEquals(SignalEvidenceType.CONFIGURED, node.evidenceType)
    }
    @Test fun externalEndpointNeverInheritsLocalPcmProcessing() {
        val g = SignalTruthGraphProjector.project(layers(),
            AudioSignalObservation(dspStatus = DspPipelineStatus(
                isReplayGainActive = true, replayGainPcmObserved = true,
                replayGainFramesProcessed = 480)), SignalDestination.REMOTE_HOST, "remote")
        assertTrue(g.nodes.none { it.stableId == "main:replaygain" })
    }
    @Test fun streamReceiverAndPhysicalDacRemainUnverified() {
        val g = SignalTruthGraphProjector.project(layers(), AudioSignalObservation(),
            SignalDestination.MICHI_STREAM, "stream")
        assertEquals(SignalEvidenceType.CONFIGURED,
            g.nodes.single { it.stableId == "rtp:rtp" }.evidenceType)
        assertEquals(SignalEvidenceType.UNKNOWN,
            g.nodes.single { it.stableId == "rtp:physical" }.evidenceType)
    }
}
