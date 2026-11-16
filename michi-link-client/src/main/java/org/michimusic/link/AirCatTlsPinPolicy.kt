package org.michimusic.link

import java.net.URI
import java.util.Base64

/**
 * A26: fail closed BEFORE constructing the first AirCat HTTP client.
 * A registry token or a signed UDP announce cannot substitute TLS identity.
 * A valid pin is a certificate SPKI SHA-256, provisioned by an authenticated
 * Link Home enrollment path; it must never be learned from an untrusted request.
 */
object AirCatTlsPinPolicy {
    fun refusal(baseUrl: String, pinnedSpkiSha256: String): String? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return "AIRC_TLS_URL_INVALID"
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port !in -1..65535 || uri.port == 0 || uri.rawPath !in listOf(null, "", "/")
        ) return "AIRC_TLS_REQUIRED"
        if (!validPin(pinnedSpkiSha256)) return "AIRC_TLS_PIN_REQUIRED"
        return null
    }

    fun validPin(pin: String): Boolean = runCatching {
        if (!pin.startsWith("sha256/")) return false
        val hash = Base64.getDecoder().decode(pin.removePrefix("sha256/"))
        hash.size == 32 && "sha256/" + Base64.getEncoder().encodeToString(hash) == pin
    }.getOrDefault(false)
}
