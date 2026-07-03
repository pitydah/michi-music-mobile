package org.michimusic.link

internal object LinkClientConfig {
    fun normalizeBaseUrl(value: String): String {
        return value.trim().trimEnd('/')
    }
}
