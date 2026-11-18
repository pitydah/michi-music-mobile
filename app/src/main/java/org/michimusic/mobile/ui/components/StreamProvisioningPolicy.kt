package org.michimusic.mobile.ui.components

/** Presentation guard only. Authentication/pairing remains enforced by the domain. */
fun isProvisionedMichiStream(deviceType: String): Boolean =
    org.michimusic.mobile.devices.link.isProvisionedMichiStream(deviceType)

const val STREAM_PROVISIONING_GUIDANCE =
    "Michi Music Stream no utiliza un botón físico de emparejamiento. " +
    "Su Wi-Fi e identidad Michi Link se configuran al grabar el firmware desde Ecosystem. " +
    "Comprueba que esté encendido, en la misma red y registrado con la identidad correcta."

/** A detected Stream can require secure pairing, but never a nonexistent button. */
fun refusesPhysicalButtonForMichiStream(
    discoveredType: String,
    serverService: String,
    reportedStrategy: String,
): Boolean = org.michimusic.mobile.devices.link.refusesPhysicalButtonForMichiStream(
    discoveredType, serverService, reportedStrategy,
)
