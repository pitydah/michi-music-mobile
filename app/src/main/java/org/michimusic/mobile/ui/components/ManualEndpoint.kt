package org.michimusic.mobile.ui.components

/** Parsed LAN endpoint for manual Michi Link pairing. The alias does not confer identity. */
data class ManualEndpoint(val host: String, val port: Int) {
    val authority: String get() = "$host:$port"
}

const val MANUAL_ENDPOINT_ERROR =
    "Ingresa una IP o nombre local y un puerto válido (1–65535). " +
    "La conexión manual IPv6 aún no está disponible."

/**
 * Accept IPv4 and LAN host names, with an optional numeric port. Fail closed on
 * malformed input rather than silently picking another endpoint/port.
 * IPv6 is intentionally rejected here: LinkClient/SyncViewModel still build
 * unbracketed IPv6 URLs. Do not advertise IPv6 until that layer supports it.
 */
fun parseManualEndpoint(input: String, defaultPort: Int = 8400): ManualEndpoint? =
    org.michimusic.mobile.devices.link.parseManualEndpoint(input, defaultPort)
        ?.let { ManualEndpoint(it.host, it.port) }
