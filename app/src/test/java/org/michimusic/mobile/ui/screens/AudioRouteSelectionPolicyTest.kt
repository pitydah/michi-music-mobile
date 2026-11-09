package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteSelectionPolicyTest {
    @Test fun physicalUsbMustBePresentInBothSources() {
        assertTrue(canRequestPhysicalUsbOutput(12, true, true, setOf(12), setOf(12)))
        assertFalse(canRequestPhysicalUsbOutput(12, true, true, setOf(12), emptySet()))
        assertFalse(canRequestPhysicalUsbOutput(12, true, true, emptySet(), setOf(12)))
        assertFalse(canRequestPhysicalUsbOutput(12, true, false, setOf(12), setOf(12)))
    }

    @Test fun bluetoothSpeakersAndSyntheticOrNegativeIdsNeverAllowSelection() {
        assertFalse(canRequestPhysicalUsbOutput(12, false, true, setOf(12), setOf(12)))
        assertFalse(canRequestPhysicalUsbOutput(1, true, true, emptySet(), setOf(1)))
        assertFalse(canRequestPhysicalUsbOutput(-1, true, true, setOf(-1), setOf(-1)))
    }

    @Test fun selectedUsbBecomesDisconnectedIfEitherInventoryLosesIt() {
        assertEquals(PreferredUsbPresence.AUTOMATIC, preferredUsbPresence(null, emptySet(), emptySet()))
        assertEquals(PreferredUsbPresence.AVAILABLE, preferredUsbPresence(7, setOf(7), setOf(7)))
        assertEquals(PreferredUsbPresence.DISCONNECTED, preferredUsbPresence(7, setOf(7), emptySet()))
        assertEquals(PreferredUsbPresence.DISCONNECTED, preferredUsbPresence(7, emptySet(), setOf(7)))
        assertEquals(PreferredUsbPresence.DISCONNECTED, preferredUsbPresence(-1, setOf(-1), setOf(-1)))
        assertTrue(preferredUsbGuidance(PreferredUsbPresence.DISCONNECTED)!!.contains("ya no aparece"))
        assertEquals(null, preferredUsbGuidance(PreferredUsbPresence.AUTOMATIC))
    }

    @Test fun savedUsbPreferenceNeverBecomesObservedRoute() {
        assertEquals("Preferencia USB guardada; la ruta efectiva sigue sin confirmarse",
            audioRouteSelectionHint(true, observedHere = false, requestedHere = false,
                preferredHere = true))
    }

    @Test fun selectionStatusNeverPromotesRequestToConfirmation() {
        assertEquals("Salida solicitada; esperando confirmación de AudioTrack",
            audioRouteSelectionHint(true, observedHere = false, requestedHere = true))
        assertEquals("Ruta de AudioTrack observada en esta salida",
            audioRouteSelectionHint(true, observedHere = true, requestedHere = true))
        assertEquals("Dispositivo detectado; no es una ruta controlable desde esta pantalla",
            audioRouteSelectionHint(false, observedHere = false, requestedHere = false))
    }
    @Test fun hotPlugIdReuseCannotSelectAnotherType() {
        val usbTypes = setOf(11, 22, 23)
        val isUsb: (Int) -> Boolean = { it in usbTypes }
        assertTrue(canRequestSamePhysicalUsbOutput(4, 11, mapOf(4 to 11), setOf(4), isUsb))
        assertFalse(canRequestSamePhysicalUsbOutput(4, 11, mapOf(4 to 22), setOf(4), isUsb))
        assertFalse(canRequestSamePhysicalUsbOutput(4, 11, mapOf(4 to 1), setOf(4), isUsb))
        assertFalse(canRequestSamePhysicalUsbOutput(4, 11, mapOf(4 to 11), emptySet(), isUsb))
        assertFalse(canRequestSamePhysicalUsbOutput(-1, 11, mapOf(-1 to 11), setOf(-1), isUsb))
    }
    @Test fun preferredUsbMustNotPromoteRecycledSpeakerId() {
        val isUsb: (Int) -> Boolean = { it == 11 }
        assertEquals(PreferredUsbPresence.AUTOMATIC,
            preferredPhysicalUsbPresence(null, emptyMap(), emptySet(), isUsb))
        assertEquals(PreferredUsbPresence.AVAILABLE,
            preferredPhysicalUsbPresence(3, mapOf(3 to 11), setOf(3), isUsb))
        assertEquals(PreferredUsbPresence.DISCONNECTED,
            preferredPhysicalUsbPresence(3, mapOf(3 to 2), setOf(3), isUsb))
        assertEquals(PreferredUsbPresence.DISCONNECTED,
            preferredPhysicalUsbPresence(3, mapOf(3 to 11), emptySet(), isUsb))
    }

    @Test fun recycledIdWithChangedDescriptorFailsClosed() {
        val displayed = UsbPhysicalDescriptor(5, 11, "usb-1", "DAC-A")
        assertTrue(matchingUsbDescriptor(displayed, UsbPhysicalDescriptor(5, 11, "usb-1", "DAC-A")))
        assertFalse(matchingUsbDescriptor(displayed, UsbPhysicalDescriptor(5, 11, "usb-2", "DAC-A")))
        assertFalse(matchingUsbDescriptor(displayed, UsbPhysicalDescriptor(5, 11, "usb-1", "DAC-B")))
        assertFalse(matchingUsbDescriptor(displayed, UsbPhysicalDescriptor(5, 22, "usb-1", "DAC-A")))
        assertFalse(matchingUsbDescriptor(displayed, null))
        assertTrue(usbNeedsConfirmation(UsbPhysicalDescriptor(5, 11, "", "DAC-A")))
        assertFalse(usbNeedsConfirmation(displayed))
    }

}
