package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalTruthVisualTrailContractTest {
    private fun step(id: String, linked: Boolean = false) = SignalTruthUiStep(
        id = id, title = id, detail = "texto de presentación", stateLabel = "Sin verificar",
        tone = SignalTruthUiTone.UNKNOWN, connectedToPrevious = linked,
    )

    @Test fun noConnectionIsInferredFromVisualAdjacency() {
        assertEquals(emptyList<Pair<String, String>>(),
            signalTruthUiConnectorPairs(listOf(step("source"), step("decode"), step("route"))))
    }

    @Test fun onlyExplicitAdjacentLinksAppear() {
        val rows = listOf(step("source"), step("decode", true),
            step("branch"), step("physical", true))
        assertEquals(listOf("source" to "decode", "branch" to "physical"),
            signalTruthUiConnectorPairs(rows))
    }

    @Test fun duplicateAndMissingIdsFailClosed() {
        assertEquals(emptyList<Pair<String, String>>(),
            signalTruthUiConnectorPairs(listOf(step("same"), step("same", true))))
        assertEquals(emptyList<Pair<String, String>>(),
            signalTruthUiConnectorPairs(listOf(step(""), step("decode", true))))
    }

    @Test fun labelsAreNotReinterpretedByTheVisualLayer() {
        val row = step("pcm")
        assertEquals("Sin verificar", row.stateLabel)
        assertEquals(SignalTruthUiTone.UNKNOWN, row.tone)
    }
}
