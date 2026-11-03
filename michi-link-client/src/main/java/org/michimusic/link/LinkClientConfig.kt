package org.michimusic.link

internal object LinkClientConfig {
    fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        validateEndpointSecurity(trimmed)
        return trimmed
    }

    fun validateEndpointSecurity(url: String) {
        val uri = runCatching { java.net.URI(url) }
            .getOrElse { throw IllegalArgumentException("URI del servidor inválida", it) }
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("Falta el esquema HTTP/HTTPS")
        require(scheme == "http" || scheme == "https") { "Solo se permiten HTTP y HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.port != 0 && uri.port <= 65535) {
            "Endpoint inválido o credenciales/fragmentos en URL"
        }
        if (scheme == "https") return
        val host = uri.host.lowercase().trim('[', ']')
        // Plain HTTP is a LAN concession, so the host must prove it is local. A
        // single-label name such as "arbitraryhost" can resolve anywhere through the
        // search domains, so it is not accepted as local.
        val isLocal = host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.endsWith(".local")
        val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }
        val privateV4 = ipv4.size == 4 && ipv4.all { it in 0..255 } &&
            (ipv4[0] == 10 || ipv4[0] == 172 && ipv4[1] in 16..31 ||
                ipv4[0] == 192 && ipv4[1] == 168)
        // Check actual IPv6 bytes, never a hostname prefix such as fdpublic.example.
        val ipv6 = if (host.contains(':')) runCatching {
            java.net.InetAddress.getByName(host) as? java.net.Inet6Address
        }.getOrNull() else null
        val octets = ipv6?.address
        val privateV6 = octets != null && (
            (octets[0].toInt() and 0xfe) == 0xfc || // fc00::/7 ULA
            ((octets[0].toInt() and 0xff) == 0xfe &&
                (octets[1].toInt() and 0xc0) == 0x80) // fe80::/10 link-local
        )
        require(isLocal || privateV4 || privateV6) {
            "HTTP no permitido para este destino; use HTTPS"
        }
    }
}
