package org.michimusic.link

import java.net.InetAddress

/**
 * A53: a valid discovery signature authenticates its contents, NOT a packet's
 * unauthenticated UDP routing origin. Never migrate a known peer's IP on a
 * signed hostname that may have been replayed by another sender.
 * No DNS, sockets, timers, background probing or Home trust elevation.
 */
internal object LinkAnnounceSourceBindingPolicy {
    private val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    private val ipv6 = Regex("[0-9a-fA-F:.]+")

    private fun literal(value: String): Boolean =
        (ipv4.matches(value) && value.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) ||
            (value.contains(':') && ipv6.matches(value))

    /** Migrations require host==UDP origin as numeric addresses, not a DNS hint. */
    fun mayMove(signedHost: String, udpSource: String): Boolean {
        if (!literal(signedHost) || !literal(udpSource)) return false
        return runCatching {
            InetAddress.getByName(signedHost) == InetAddress.getByName(udpSource)
        }.getOrDefault(false)
    }
}
