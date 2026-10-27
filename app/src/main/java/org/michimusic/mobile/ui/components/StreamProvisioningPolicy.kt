package org.michimusic.mobile.ui.components

/** Presentation guard only. Authentication/pairing remains enforced by the domain. */
fun isProvisionedMichiStream(deviceType: String): Boolean {
    val type = deviceType.trim().lowercase().replace('-', '_')
    // Match explicit receiver types; never infer from user-editable display names.
    return type == "michi_stream" || type.startsWith("michi_stream_") ||
        type == "michi_music_stream" || type.startsWith("michi_music_stream_")
}

const val STREAM_PROVISIONING_GUIDANCE =
    "Michi Music Stream no utiliza un botón físico de emparejamiento. " +
    "Su Wi-Fi e identidad Michi Link se configuran al grabar el firmware desde Ecosystem. " +
    "Comprueba que esté encendido, en la misma red y registrado con la identidad correcta."

/** A detected Stream can require secure pairing, but never a nonexistent button. */
fun refusesPhysicalButtonForMichiStream(
    discoveredType: String,
    serverService: String,
    reportedStrategy: String,
): Boolean {
    val isStream = isProvisionedMichiStream(discoveredType) ||
        discoveredType.trim().equals("stream", ignoreCase = true) ||
        isProvisionedMichiStream(serverService)
    return isStream && reportedStrategy.equals("RECEIVER_BUTTON", ignoreCase = true)
}
