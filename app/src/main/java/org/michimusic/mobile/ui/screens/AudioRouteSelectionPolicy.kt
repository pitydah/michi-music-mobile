package org.michimusic.mobile.ui.screens

/**
 * The Android device list is advisory. A USB routing request is available only
 * when the physical AudioManager snapshot AND UsbDacManager agree about the ID.
 * Never expose Bluetooth, speaker or a synthetic fallback row as selectable.
 */
internal fun canRequestPhysicalUsbOutput(
    deviceId: Int,
    isUsbType: Boolean,
    managerPresent: Boolean,
    physicalOutputIds: Set<Int>,
    managedUsbOutputIds: Set<Int>,
): Boolean = managerPresent && isUsbType && deviceId >= 0 &&
    deviceId in physicalOutputIds && deviceId in managedUsbOutputIds

/** A request must never be described as effective audio routing evidence. */
internal fun audioRouteSelectionHint(
    selectable: Boolean,
    observedHere: Boolean,
    requestedHere: Boolean,
    preferredHere: Boolean = false,
): String = when {
    observedHere -> "Ruta de AudioTrack observada en esta salida"
    requestedHere -> "Salida solicitada; esperando confirmación de AudioTrack"
    preferredHere -> "Preferencia USB guardada; la ruta efectiva sigue sin confirmarse"
    selectable -> "Solicitar esta salida USB para la próxima sesión de reproducción"
    else -> "Dispositivo detectado; no es una ruta controlable desde esta pantalla"
}

/** Selected USB preference may outlive a hot-unplug; never present it as routed. */
internal enum class PreferredUsbPresence { AUTOMATIC, AVAILABLE, DISCONNECTED }

internal fun preferredUsbPresence(
    selectedUsbId: Int?,
    physicalOutputIds: Set<Int>,
    managedUsbOutputIds: Set<Int>,
): PreferredUsbPresence = when {
    selectedUsbId == null -> PreferredUsbPresence.AUTOMATIC
    selectedUsbId < 0 || selectedUsbId !in physicalOutputIds ||
        selectedUsbId !in managedUsbOutputIds -> PreferredUsbPresence.DISCONNECTED
    else -> PreferredUsbPresence.AVAILABLE
}

internal fun preferredUsbGuidance(presence: PreferredUsbPresence): String? = when (presence) {
    PreferredUsbPresence.AUTOMATIC -> null
    PreferredUsbPresence.AVAILABLE ->
        "Salida USB preferida disponible. Solo la ruta de AudioTrack confirma su uso."
    PreferredUsbPresence.DISCONNECTED ->
        "La salida USB preferida ya no aparece entre las salidas disponibles. " +
            "Android puede usar otra ruta; vuelve a Automático o conecta el dispositivo."
}

/** Verify both live inventories AND the physical type, not only a recyclable Android ID. */
internal fun canRequestSamePhysicalUsbOutput(
    displayedDeviceId: Int,
    displayedDeviceType: Int,
    liveOutputTypes: Map<Int, Int>,
    liveManagedUsbIds: Set<Int>,
    isUsbAudioType: (Int) -> Boolean,
): Boolean = displayedDeviceId >= 0 && isUsbAudioType(displayedDeviceType) &&
    liveOutputTypes[displayedDeviceId] == displayedDeviceType &&
    displayedDeviceId in liveManagedUsbIds

/** Never call a recycled Bluetooth or built-in speaker ID an available USB preference. */
internal fun preferredPhysicalUsbPresence(
    selectedUsbId: Int?,
    currentSystemOutputTypes: Map<Int, Int>,
    managedUsbIds: Set<Int>,
    isUsbAudioType: (Int) -> Boolean,
): PreferredUsbPresence = when {
    selectedUsbId == null -> PreferredUsbPresence.AUTOMATIC
    currentSystemOutputTypes[selectedUsbId]?.let(isUsbAudioType) != true ||
        selectedUsbId !in managedUsbIds -> PreferredUsbPresence.DISCONNECTED
    else -> PreferredUsbPresence.AVAILABLE
}

/** A numeric ID is transient. Validate the live descriptor; blank addresses require UI consent. */
internal data class UsbPhysicalDescriptor(
    val id: Int, val type: Int, val address: String, val product: String,
)

internal fun matchingUsbDescriptor(
    shown: UsbPhysicalDescriptor, current: UsbPhysicalDescriptor?,
): Boolean = current != null && shown.id >= 0 && shown.id == current.id &&
    shown.type == current.type && shown.address == current.address &&
    shown.product == current.product

/** No stable address: ask the user to confirm the new *current* device. */
internal fun usbNeedsConfirmation(descriptor: UsbPhysicalDescriptor): Boolean =
    descriptor.address.isBlank()
