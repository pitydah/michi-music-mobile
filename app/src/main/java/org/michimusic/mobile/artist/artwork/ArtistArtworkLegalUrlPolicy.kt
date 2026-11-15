package org.michimusic.mobile.artist.artwork

import java.net.URI

/** One exact legal-deed parser shared by Openverse licence and origin checks.
 * Never infer an absent version, silently discard an extra URL segment or accept
 * an unreviewed version, port, query, country-port or Creative Commons derivative.
 */
internal object ArtistArtworkLegalUrlPolicy {
    private val published = setOf("1.0", "2.0", "2.5", "3.0", "4.0")

    fun canonicalKey(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 300) return null
        val url = try { URI(raw.trim()) } catch (_: Exception) { return null }
        if (url.scheme != "https" || !url.host.equals("creativecommons.org", ignoreCase = true) ||
            url.port != -1 || url.rawUserInfo != null || url.rawQuery != null ||
            url.rawFragment != null) return null
        val segments = url.path.orEmpty().trim('/').split('/')
        if (segments.size != 3) return null
        return when {
            segments == listOf("publicdomain", "mark", "1.0") -> "publicdomain"
            segments == listOf("publicdomain", "zero", "1.0") -> "cc0-1.0"
            segments[0] == "licenses" && segments[1] in setOf("by", "by-sa") &&
                segments[2] in published -> "${segments[1]}-${segments[2]}"
            else -> null
        }
    }
}
