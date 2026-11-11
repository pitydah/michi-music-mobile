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

    @Test fun buildModelCodeIsNeverPresentedAsHumanOutput() {
        val output = audioRouteEvidence(
            AudioRouteStatus.Confirmed(OutputDescriptor(1, "sdk_gphone64_x86_64", 2), 5),
        )
        assertEquals("Altavoz del teléfono", output.deviceLabel)
        assertFalse(output.deviceLabel.contains("sdk_"))
    }

    @Test fun observedUsbHumanNameIsPreserved() {
        val output = audioRouteEvidence(
            AudioRouteStatus.Confirmed(OutputDescriptor(9, "Real DAC", 11), 5),
        )
        assertEquals("Real DAC", output.deviceLabel)
    }

    @Test fun noDeviceIsNotAnInternalSpeakerClaim() {
        val output = audioRouteEvidence(AudioRouteStatus.NoDevice)
        assertFalse(output.hasCurrentEvidence)
        assertFalse(output.deviceLabel.contains("Altavoz"))
    }

    @Test fun connectedDacCannotInheritOtherOutputConfirmation() {
        val dacA = OutputDescriptor(7, "DAC A", 11)
        val phone = OutputDescriptor(8, "Phone", 2)
        assertEquals("DISPONIBLE", usbDacRouteLabel(AudioRouteStatus.Confirmed(phone, 50), dacA.androidDeviceId))
        assertEquals("EN USO", usbDacRouteLabel(AudioRouteStatus.Confirmed(dacA, 50), dacA.androidDeviceId))
        assertEquals("SOLICITADO", usbDacRouteLabel(AudioRouteStatus.Requested(dacA), dacA.androidDeviceId))
        assertEquals("RUTA DIFERENTE", usbDacRouteLabel(AudioRouteStatus.RoutedElsewhere(dacA, phone, 50), dacA.androidDeviceId))
        assertTrue(isObservedOutputInUse(AudioRouteStatus.RoutedElsewhere(dacA, phone, 50), phone.androidDeviceId))
        assertFalse(isObservedOutputInUse(AudioRouteStatus.Requested(dacA), dacA.androidDeviceId))
    }
}
