package org.michimusic.mobile.ui.components

import org.junit.Assert.*
import org.junit.Test
import org.michimusic.player.*

class AudioRouteEvidenceTest {
    private fun dac(name: String) = OutputDescriptor(7, name, 11)

    @Test fun detectionIsNeverCurrentRouteProof() {
        val output = audioRouteEvidence(AudioRouteStatus.Detected(dac("USB DAC")))
        assertFalse(output.hasCurrentEvidence)
        assertTrue(output.headline.contains("detectado", ignoreCase = true))
    }

    @Test fun requestedIsNeverCurrentRouteProof() {
        assertFalse(audioRouteEvidence(AudioRouteStatus.Requested(dac("USB DAC"))).hasCurrentEvidence)
    }

    @Test fun confirmedIsEvidenceButDoesNotPromiseBitPerfect() {
        val output = audioRouteEvidence(AudioRouteStatus.Confirmed(dac("DAC"), 5))
        assertTrue(output.hasCurrentEvidence)
        assertTrue(output.explanation.contains("No certifica"))
    }

    @Test fun routedElsewhereDisplaysActualNotRequested() {
        val output = audioRouteEvidence(AudioRouteStatus.RoutedElsewhere(dac("Deseado"), dac("Real"), 6))
        assertEquals("Real", output.deviceLabel)
        assertTrue(output.explanation.contains("Deseado"))
    }

    @Test fun historicalRouteIsNotCurrentEvidence() {
        assertFalse(audioRouteEvidence(AudioRouteStatus.LastObserved(dac("Histórico"), 2)).hasCurrentEvidence)
    }

    @Test fun errorAndUnknownAreNotConfirmed() {
        assertFalse(audioRouteEvidence(AudioRouteStatus.Failed(7, RouteFailure.DISCONNECTED)).hasCurrentEvidence)
        assertFalse(audioRouteEvidence(AudioRouteStatus.Unknown("Sin datos")).hasCurrentEvidence)
    }

    @Test fun noDeviceIsNotAnInternalSpeakerClaim() {
        val output = audioRouteEvidence(AudioRouteStatus.NoDevice)
        assertFalse(output.hasCurrentEvidence)
        assertFalse(output.deviceLabel.contains("Altavoz"))
    }
}
