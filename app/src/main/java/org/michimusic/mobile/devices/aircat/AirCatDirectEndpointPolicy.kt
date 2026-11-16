package org.michimusic.mobile.devices.aircat

import java.net.URI
import java.util.Locale
import org.michimusic.link.AirCatTlsPinPolicy

/** A63: transport endpoint is a saved Home binding, never a mutable UDP hint. */
internal object AirCatDirectEndpointPolicy {
    /** No network lookup or scan: only locally stored URLs plus a validated SPKI. */
    fun refusal(enrolledUrl: String, resolvedUrl: String, enrolledSpki: String): String? {
        if (AirCatTlsPinPolicy.refusal(enrolledUrl, enrolledSpki) != null)
            return "AIRC_DIRECT_HOME_PIN_REQUIRED"
        if (AirCatTlsPinPolicy.refusal(resolvedUrl, enrolledSpki) != null)
            return "AIRC_DIRECT_TLS_REFUSED"
        fun normalized(raw: String): String? = runCatching {
            val u = URI(raw)
            if (u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null ||
                u.rawPath !in listOf(null, "", "/") || u.port == 0 ||
                u.port > 65535 || u.port < -1) return@runCatching null
            val host = u.host?.lowercase(Locale.ROOT) ?: return@runCatching null
            if (host.isBlank() || host.contains('%')) return@runCatching null
            "https://${if (host.contains(':')) "[${host.removePrefix("[").removeSuffix("]")}]" else host}:${if (u.port == -1) 443 else u.port}"
        }.getOrNull()
        val original = normalized(enrolledUrl) ?: return "AIRC_DIRECT_HOME_URL_INVALID"
        val candidate = normalized(resolvedUrl) ?: return "AIRC_DIRECT_RESOLVED_URL_INVALID"
        // Even a legitimate peer announced at another IP cannot move a pinned
        // source automatically. The administrator must explicitly re-enrol it.
        if (original != candidate) return "AIRC_DIRECT_ENDPOINT_CHANGED"
        return null
    }
}
