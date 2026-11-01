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
        assertEquals("Altavoz del teléfono · ruta 1", audioOutputFriendlyName(first))
        assertEquals("Altavoz del teléfono · ruta 4", audioOutputFriendlyName(second))
    }

    @Test fun bluetoothPreservesActualDeviceName() {
        val device = AudioOutputDevice(9, "Michi Headset", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, isBluetooth = true)
        assertEquals("Michi Headset", audioOutputFriendlyName(device))
    }

    @Test fun unknownTypeNeverPretendsToBeSpeaker() {
        val device = AudioOutputDevice(5, "", 9999)
        assertEquals("Salida de audio · ruta 5", audioOutputFriendlyName(device))
    }
}
