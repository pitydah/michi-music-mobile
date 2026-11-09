package org.michimusic.mobile.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.OutputDescriptor
import org.michimusic.player.UsbMixerState

class UsbDacMixerControlPolicyTest {
    private val usb = OutputDescriptor(101, "DAC USB", AudioDeviceInfo.TYPE_USB_DEVICE)
    private val otherUsb = OutputDescriptor(202, "Otro DAC", AudioDeviceInfo.TYPE_USB_HEADSET)
    private val speaker = OutputDescriptor(9, "Altavoz", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val mixer = UsbMixerState(deviceId = 101, supportedRates = listOf(44100, 48000, 96000))

    @Test fun unsupportedAndroidVersionIsReadOnly() {
        assertFalse(usbMixerControlAvailability(33, AudioRouteStatus.Confirmed(usb, 1234), mixer).editable)
    }

    @Test fun detectedOrRequestedDacDoesNotUnlockMixer() {
        assertFalse(usbMixerControlAvailability(34, AudioRouteStatus.Detected(usb), mixer).editable)
        assertFalse(usbMixerControlAvailability(34, AudioRouteStatus.Requested(usb), mixer).editable)
    }

    @Test fun physicalDeviceSelectionMustMatchTheObservedRoute() {
        assertFalse(usbMixerControlAvailability(34, AudioRouteStatus.Confirmed(otherUsb, 1234), mixer).editable)
        assertFalse(usbMixerControlAvailability(34, AudioRouteStatus.Confirmed(speaker, 1234), mixer).editable)
    }

    @Test fun noSupportedNormalMixerProfileMeansNoRateControl() {
        assertFalse(usbMixerControlAvailability(34, AudioRouteStatus.Confirmed(usb, 1234),
            mixer.copy(supportedRates = emptyList())).editable)
    }

    @Test fun confirmedUsbWithMatchingDeviceAndRatesCanRequestPreference() {
        assertTrue(usbMixerControlAvailability(34, AudioRouteStatus.Confirmed(usb, 1234), mixer).editable)
    }
}
