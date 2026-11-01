package org.michimusic.mobile.ui.components

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.player.AudioOutputDevice

class AudioOutputNamingPolicyTest {
    @Test fun usbHeadsetIsNotPresentedAsBuiltInSpeaker() {
        val device = AudioOutputDevice(23, "", AudioDeviceInfo.TYPE_USB_HEADSET, isUsbHeadset = true)
        assertEquals("Auriculares USB", audioOutputFriendlyName(device))
    }

    @Test fun multipleSpeakersHaveDistinctHonestLabels() {
        val first = AudioOutputDevice(1, "sdk_gphone64_x86_64", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, isSpeaker = true)
        val second = first.copy(id = 4)
        assertEquals("Altavoz del teléfono", audioOutputFriendlyName(first))
        assertEquals("Altavoz del teléfono", audioOutputFriendlyName(second))
    }

    @Test fun collidingLabelsAreDistinctWithoutExposingAndroidRouteIds() {
        val a = AudioOutputDevice(8, "sdk_gphone64_x86_64", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, isSpeaker = true)
        val b = a.copy(id = 4)
        val labels = audioOutputDisambiguatedNames(listOf(a,b))
        assertEquals("Altavoz del teléfono (1)", labels[4])
        assertEquals("Altavoz del teléfono (2)", labels[8])
        assertEquals(2, labels.values.toSet().size)
    }

    @Test fun uniqueHardwareNameRemainsUnchangedInOutputList() {
        val device = AudioOutputDevice(9, "FiiO KA17", AudioDeviceInfo.TYPE_USB_DEVICE, isUsbDac = true)
        assertEquals("FiiO KA17", audioOutputDisambiguatedNames(listOf(device))[device.id])
    }

    @Test fun bluetoothPreservesActualDeviceName() {
        val device = AudioOutputDevice(9, "Michi Headset", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isBluetooth = true)
        assertEquals("Michi Headset", audioOutputFriendlyName(device))
    }

    @Test fun emulatorIdentifierNeverLeaksIntoConsumerName() {
        val device = AudioOutputDevice(7, "sdk_gphone64_x86_64", 9999)
        assertEquals("Otra salida de audio", audioOutputFriendlyName(device))
    }

    @Test fun realManufacturerNameSurvivesNormalization() {
        val device = AudioOutputDevice(8, "FiiO KA17", AudioDeviceInfo.TYPE_USB_DEVICE, isUsbDac = true)
        assertEquals("FiiO KA17", audioOutputFriendlyName(device))
    }

    @Test fun unknownTypeNeverPretendsToBeSpeaker() {
        val device = AudioOutputDevice(5, "", 9999)
        assertEquals("Otra salida de audio", audioOutputFriendlyName(device))
    }
}
