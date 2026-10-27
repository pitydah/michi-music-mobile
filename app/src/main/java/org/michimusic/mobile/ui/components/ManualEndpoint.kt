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
fun parseManualEndpoint(input: String, defaultPort: Int = 8400): ManualEndpoint? {
    if (defaultPort !in 1..65535) return null
    val raw = input.trim()
    if (raw.isEmpty() || raw.length > 260 || raw.contains("://") ||
        raw.any { it.isWhitespace() || it in "/\\?#@[]" }) return null
    if (raw.count { it == ':' } > 1) return null
    val hasPort = ':' in raw
    val host = raw.substringBefore(':')
    val portText = if (hasPort) raw.substringAfter(':') else ""
    val port = if (hasPort) {
        if (portText.isEmpty() || !portText.all(Char::isDigit)) return null
        portText.toIntOrNull() ?: return null
    } else defaultPort
    if (port !in 1..65535 || host.isEmpty() || host.length > 253 ||
        host.startsWith('.') || host.endsWith('.') || host.contains("..") ||
        host.any { !it.isLetterOrDigit() && it != '-' && it != '.' }) return null
    // Numeric dotted-quad input must be a valid IPv4 address, not an arbitrary hostname.
    if (host.all { it.isDigit() || it == '.' }) {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
            it.toIntOrNull()?.let { octet -> octet !in 0..255 } != false }) return null
    }
    if (host.split('.').any { it.startsWith('-') || it.endsWith('-') }) return null
    return ManualEndpoint(host.lowercase(), port)
}
