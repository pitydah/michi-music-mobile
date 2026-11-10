package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.SignalEvidenceType

class SignalTruthChainPresentationTest {
    private fun node(id: String, branch: String, evidence: SignalEvidenceType = SignalEvidenceType.UNKNOWN) =
        SignalStageUi(id, branch, "Nombre", "No observado", evidence, "local_phone", 0L)

    @Test fun preservesDifferentBranchesWithoutInventingConnectors() {
        val stages = listOf(node("main:source", "main"), node("main:decode", "main"),
            node("rtp:sender", "rtp"), node("local:monitor", "local"))
        val sections = SignalTruthChainPresentation.sections(stages)
        assertEquals(listOf("main", "rtp", "local"), sections.map { it.branchId })
        assertEquals(listOf("main:source", "main:decode"), sections.first().stages.map { it.stableId })
        assertEquals("Rama Michi Stream · envío RTP", SignalTruthChainPresentation.branchTitle("rtp"))
    }

    @Test fun interleavedBranchRetainsOrderInsteadOfMovingNodes() {
        val stages = listOf(node("main:1", "main"), node("rtp:2", "rtp"), node("main:3", "main"))
        assertEquals(listOf("main", "rtp", "main"),
            SignalTruthChainPresentation.sections(stages).map { it.branchId })
        assertEquals(stages.map { it.stableId },
            SignalTruthChainPresentation.sections(stages).flatMap { it.stages }.map { it.stableId })
    }

    @Test fun unknownIsStillUnknownAndHasNoInventedNumericFormat() {
        val stage = node("main:decode", "main")
        assertEquals(SignalEvidenceType.UNKNOWN, stage.evidenceType)
        assertEquals("Sin medición PCM de esta pista", SignalTruthChainPresentation.summary(stage))
        assertEquals("Decodificación PCM", SignalTruthChainPresentation.label(stage))
    }

    @Test fun actionableStagesExposeClearControlNamesWithoutNewDspEngines() {
        assertEquals("Editar ecualizador", SignalTruthChainPresentation.actionLabel(SignalStageAction.OPEN_EQ))
        assertEquals("Elegir perfil", SignalTruthChainPresentation.actionLabel(SignalStageAction.HEADPHONE_PROFILE))
        assertEquals("Ajustar ReplayGain", SignalTruthChainPresentation.actionLabel(SignalStageAction.REPLAY_GAIN))
        assertEquals("Ajustar mezclador USB", SignalTruthChainPresentation.actionLabel(SignalStageAction.USB_MIXER))
        assertNull(SignalTruthChainPresentation.actionLabel(SignalStageAction.NONE))
    }
}
