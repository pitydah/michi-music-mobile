package org.michimusic.mobile.ui.components

import android.media.AudioDeviceInfo
import org.michimusic.player.AudioOutputDevice

/**
 * Naming reflects the actual Android device type. Device IDs disambiguate
 * routes without leaking emulator product names as a false consumer label.
 * The selected route remains an audio-routing *request*, not verified DAC truth.
 */
fun audioOutputFriendlyName(device: AudioOutputDevice): String {
    val product = device.name.trim()
    return when {
        device.isUsbHeadset -> product.takeIf { it.isNotBlank() } ?: "Auriculares USB"
        device.isUsbDac -> product.takeIf { it.isNotBlank() } ?: "DAC USB"
        device.isBluetooth -> product.takeIf { it.isNotBlank() } ?: "Salida Bluetooth"
        device.isWired -> product.takeIf { it.isNotBlank() } ?: "Auriculares con cable"
        device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular del teléfono · ruta ${device.id}"
        device.isSpeaker -> "Altavoz del teléfono · ruta ${device.id}"
        else -> product.takeIf { it.isNotBlank() } ?: "Salida de audio · ruta ${device.id}"
    }
}
