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

class SignalTruthGraphHeadphoneReceiptTest {
    private fun project(dsp: DspPipelineStatus, mode: SignalDestination = SignalDestination.LOCAL) =
        SignalTruthGraphProjector.project(
            provenance = SignalLayer.values().map { layer ->
                SignalProvenanceItem(layer, layer.name, "No observado",
                    SignalEvidenceType.UNKNOWN, "local_phone")
            },
            observation = AudioSignalObservation(dspStatus = dsp),
            destination = mode, ownerKey = "track/epoch",
        )

    @Test fun selectedButDisconnectedProfileIsConfiguredNotObserved() {
        val node = project(DspPipelineStatus(headphonePeqConfigured = true))
            .nodes.single { it.stableId == "main:peq" }
        assertEquals(SignalEvidenceType.CONFIGURED, node.evidenceType)
        assertTrue(node.summary.contains("pendiente"))
    }
    @Test fun processorRevisionWithFramesCanBeObserved() {
        val node = project(DspPipelineStatus(headphonePeqConfigured = true,
            headphonePeqActive = true, headphonePeqAppliedRevision = 7,
            headphoneFramesProcessed = 1024L, headphonePeqObservedAtMs = 180000L))
            .nodes.single { it.stableId == "main:peq" }
        assertEquals(SignalEvidenceType.OBSERVED, node.evidenceType)
        assertTrue(node.summary.contains("PCM procesado"))
        assertTrue(node.note!!.contains("no"))
        assertEquals(180000L, node.freshnessMs)
    }
    @Test fun appliedWithoutCurrentRevisionFailsClosed() {
        val node = project(DspPipelineStatus(headphonePeqActive = true,
            headphoneFramesProcessed = 1024L, headphonePeqAppliedRevision = -1))
            .nodes.single { it.stableId == "main:peq" }
        assertEquals(SignalEvidenceType.CONFIGURED, node.evidenceType)
    }
    @Test fun appliedWithoutFramesFailsClosed() {
        val node = project(DspPipelineStatus(headphonePeqActive = true,
            headphonePeqAppliedRevision = 2, headphoneFramesProcessed = 0))
            .nodes.single { it.stableId == "main:peq" }
        assertEquals(SignalEvidenceType.CONFIGURED, node.evidenceType)
    }
    @Test fun revisionWithoutOwnTimestampIsNotObserved() {
        val node = project(DspPipelineStatus(headphonePeqActive = true,
            headphonePeqAppliedRevision = 2, headphoneFramesProcessed = 1024L))
            .nodes.single { it.stableId == "main:peq" }
        assertEquals(SignalEvidenceType.CONFIGURED, node.evidenceType)
    }
    @Test fun offAndNotSelectedOmitsPeqStage() {
        assertFalse(project(DspPipelineStatus()).nodes.any { it.stableId == "main:peq" })
    }
    @Test fun rtpNeverAttributesLocalPeqEvenWithReceipt() {
        val result = project(DspPipelineStatus(headphonePeqConfigured = true,
            headphonePeqActive = true, headphonePeqAppliedRevision = 2,
            headphoneFramesProcessed = 1024L), SignalDestination.MICHI_STREAM)
        assertFalse(result.nodes.any { it.stableId.contains(":peq") })
        assertTrue(result.nodes.any { it.stableId == "rtp:rtp" })
    }
    @Test fun remoteHostNeverInheritsPhoneProfile() {
        val result = project(DspPipelineStatus(headphonePeqConfigured = true),
            SignalDestination.REMOTE_HOST)
        assertFalse(result.nodes.any { it.stableId == "main:peq" })
    }
}
