package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI may shorten repetitive labels but can never promote detection to evidence. */
class UI8AudioRouteCompactHintTest {
    @Test fun observedUsbDoesNotRepeatObservedStatus() {
        assertNull(compactAudioRouteHint(true, true, true, false))
    }
    @Test fun requestedUsbDoesNotRepeatRequestedStatus() {
        assertNull(compactAudioRouteHint(true, true, false, true))
    }
    @Test fun nonUsbHasNoRequestHint() {
        assertNull(compactAudioRouteHint(false, false, false, false))
    }
    @Test fun usbAvailableOffersRequestNotConfirmation() {
        val hint = compactAudioRouteHint(true, true, false, false)!!
        assertTrue(hint.contains("Solicitar"))
        assertTrue(!hint.contains("observada"))
    }
    @Test fun usbPreferredIsStillUnconfirmed() {
        val hint = compactAudioRouteHint(true, true, false, false, preferredHere = true)!!
        assertTrue(hint.contains("preferencia", ignoreCase = true))
        assertTrue(hint.contains("sin confirmarse"))
    }
    @Test fun disconnectedUsbCannotBeSelected() {
        val hint = compactAudioRouteHint(true, false, false, false)!!
        assertTrue(hint.contains("no es una ruta controlable"))
    }
    @Test fun blockedObservedNeverBecomesSelectable() {
        assertNull(compactAudioRouteHint(true, false, true, false))
    }
    @Test fun routedElsewhereRequestedDeviceHasNoMisleadingWaitingHint() {
        assertNull(compactAudioRouteHint(true, true, false, true, preferredHere = true))
    }
    @Test fun falseUsbPreferenceCannotInventRoute() {
        assertNull(compactAudioRouteHint(false, true, false, false, preferredHere = true))
    }
}
