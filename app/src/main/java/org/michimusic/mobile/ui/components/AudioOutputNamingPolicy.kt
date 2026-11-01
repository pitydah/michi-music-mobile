package org.michimusic.mobile.ui.components

import android.media.AudioDeviceInfo
import org.michimusic.player.AudioOutputDevice

/** Public name is not an internal Android route ID, emulator SKU, or verified DAC claim. */
fun isInternalAudioProductName(name: String): Boolean {
    val normalized = name.trim().lowercase()
    return normalized.isBlank() || normalized.startsWith("sdk_gphone") ||
        normalized.startsWith("generic_") || normalized.startsWith("goldfish") ||
        normalized.startsWith("ranchu") || normalized.startsWith("emulator") ||
        normalized == "unknown" || normalized == "default"
}

fun audioOutputFriendlyName(device: AudioOutputDevice): String {
    val product = device.name.trim().takeUnless(::isInternalAudioProductName)
    return when {
        device.isUsbHeadset -> product ?: "Auriculares USB"
        device.isUsbDac -> product ?: "DAC USB"
        device.isBluetooth -> product ?: "Salida Bluetooth"
        device.isWired -> product ?: "Auriculares con cable"
        device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular del teléfono"
        device.isSpeaker -> "Altavoz del teléfono"
        else -> product ?: "Otra salida de audio"
    }
}


/**
 * Consumer-facing labels for multiple outputs. Duplicate names receive stable,
 * nontechnical ordinals; Android IDs are retained for routing but never printed.
 */
fun audioOutputDisambiguatedNames(devices: List<AudioOutputDevice>): Map<Int, String> {
    val grouped = devices.groupBy(::audioOutputFriendlyName)
    val result = mutableMapOf<Int, String>()
    grouped.forEach { (label, sameNameDevices) ->
        sameNameDevices.sortedBy { it.id }.forEachIndexed { index, device ->
            result[device.id] = if (sameNameDevices.size > 1) "$label (${index + 1})" else label
        }
    }
    return result
}
