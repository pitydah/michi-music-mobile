package org.michimusic.mobile.artist.artwork

import java.net.URI
import java.util.Locale

/**
 * Strict Commons Creative Commons license consistency. A verified legal-code URL
 * is the authority. Display names can be absent or known equivalents, but cannot
 * upgrade NC/ND, spoof the host, or assert a different family/version.
 */
internal object CommonsLicenseConsistency {
    // Published generic (unported/international) licence families. Unknown future
    // versions and country-ported variants require explicit legal review.
    private val publishedVersions = setOf("1.0", "2.0", "2.5", "3.0", "4.0")

    fun acceptedLabel(shortName: String?, licenseUrl: String?): String? {
        if (licenseUrl.isNullOrBlank() || licenseUrl.length > 300) return null
        val uri = try { URI(licenseUrl.trim()) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || !uri.host.equals("creativecommons.org", ignoreCase = true) ||
            uri.port != -1 || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        val segments = uri.path.orEmpty().trim('/').split('/')
        val canonical = when {
            segments == listOf("publicdomain", "mark", "1.0") -> "Public domain"
            segments == listOf("publicdomain", "zero", "1.0") -> "CC0"
            segments.size == 3 && segments[0] == "licenses" &&
                segments[1] in setOf("by", "by-sa") && segments[2] in publishedVersions ->
                if (segments[1] == "by") "CC BY ${segments[2]}" else "CC BY-SA ${segments[2]}"
            else -> return null
        }
        val supplied = shortName?.trim()?.takeIf(String::isNotEmpty)
            ?.replace(Regex("[\\s_-]+"), " ")?.lowercase(Locale.ROOT)
            ?: return canonical
        val aliases = when (canonical) {
            "CC0" -> setOf("cc0", "cc0 1.0", "cc zero", "creative commons zero")
            "Public domain" -> setOf("public domain", "public domain mark", "public domain mark 1.0", "pdm")
            else -> setOf(canonical.lowercase(Locale.ROOT).replace("-", " "))
        }
        return canonical.takeIf { supplied in aliases }
    }
}
