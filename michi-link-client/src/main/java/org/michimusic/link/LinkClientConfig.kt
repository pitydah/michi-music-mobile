package org.michimusic.link

internal object LinkClientConfig {
    fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        validateEndpointSecurity(trimmed)
        return trimmed
    }

    fun validateEndpointSecurity(url: String) {
        if (!url.startsWith("http://", ignoreCase = true)) return
        val host = runCatching { java.net.URI(url).host?.lowercase() ?: "" }.getOrDefault("")
        if (host.isEmpty()) return

        val isLocalhost = host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local") || !host.contains(".")
        val isPrivateIp = host.matches(Regex("^(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3})$"))

        if (!isLocalhost && !isPrivateIp) {
            throw IllegalArgumentException("Endpoints públicos en texto claro (HTTP) no están permitidos por seguridad: $host. Use HTTPS.")
        }
    }
}
