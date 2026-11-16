package org.michimusic.mobile.devices.link

/** A network endpoint observed or typed by a user. It carries NO Michi Link trust. */
data class ParsedLinkEndpoint(val host: String, val port: Int) {
    val authority: String get() = "$host:$port"
}

/** Parse only unambiguous IPv4 and LAN host names; IPv6 remains unsupported by the current URL builder. */
fun parseManualEndpoint(input: String, defaultPort: Int = 8400): ParsedLinkEndpoint? {
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
    if (host.all { it.isDigit() || it == '.' }) {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
            it.toIntOrNull()?.let { octet -> octet !in 0..255 } != false }) return null
    }
    if (host.split('.').any { it.startsWith('-') || it.endsWith('-') }) return null
    return ParsedLinkEndpoint(host.lowercase(), port)
}

/** Endpoint comparison is an untrusted network hint; NEVER use it for authentication. */
fun sameDiscoveredEndpoint(
    inputHost: String, inputPort: Int, discoveredHost: String, discoveredPort: Int,
): Boolean {
    if (inputPort !in 1..65535 || discoveredPort !in 1..65535 || inputPort != discoveredPort) return false
    val host = inputHost.trim().removePrefix("[").removeSuffix("]")
    val observed = discoveredHost.trim().removePrefix("[").removeSuffix("]")
    return host.isNotEmpty() && observed.isNotEmpty() && host.equals(observed, ignoreCase = true)
}

/** Two distinct advertised Michi IDs must not be merged because an IP was reused. */
fun matchesObservedPeer(
    knownId: String, knownHost: String, knownPort: Int,
    observedId: String, observedHost: String, observedPort: Int,
): Boolean {
    if (knownId.isNotBlank() && observedId.isNotBlank()) return knownId == observedId
    return sameDiscoveredEndpoint(knownHost, knownPort, observedHost, observedPort)
}

/** Stream identity is a product-type hint, not proof of pairing, trust or authorization. */
fun isProvisionedMichiStream(deviceType: String): Boolean {
    val type = deviceType.trim().lowercase().replace('-', '_')
    return type == "michi_stream" || type.startsWith("michi_stream_") ||
        type == "michi_music_stream" || type.startsWith("michi_music_stream_")
}

/** Never expose physical-button pairing for newly provisioned Michi Stream. */
fun refusesPhysicalButtonForMichiStream(
    discoveredType: String, serverService: String, reportedStrategy: String,
): Boolean {
    val isStream = isProvisionedMichiStream(discoveredType) ||
        discoveredType.trim().equals("stream", ignoreCase = true) ||
        isProvisionedMichiStream(serverService)
    return isStream && reportedStrategy.equals("RECEIVER_BUTTON", ignoreCase = true)
}
