package org.michimusic.mobile.artist.artwork

import org.michimusic.mobile.artist.allowedPortraitLicense

/**
 * Openverse is a discovery index. A URL for CC0 cannot validate a result labelled
 * CC BY, and a BY licence cannot validate BY-SA. Exact family and version must agree.
 * The Flickr origin page is checked AGAIN on explicit selection, before download.
 */
internal object OpenverseLicenseConsistency {
    fun acceptedLabel(license: String, version: String?, licenseUrl: String): String? {
        val key = licenseKey(licenseUrl) ?: return null
        val normalized = license.trim().lowercase(java.util.Locale.ROOT)
        val declaredVersion = version?.trim()?.takeIf(String::isNotBlank)
        val label = when (normalized) {
            "pdm" -> if (key == "publicdomain" && declaredVersion == null) "Public domain" else null
            "cc0" -> if (key == "cc0-1.0" && (declaredVersion == null || declaredVersion == "1.0")) "CC0" else null
            "by", "by-sa" -> {
                val prefix = "$normalized-"
                if (!key.startsWith(prefix)) return null
                val actualVersion = key.removePrefix(prefix)
                if (declaredVersion != null && declaredVersion != actualVersion) return null
                if (normalized == "by") "CC BY $actualVersion" else "CC BY-SA $actualVersion"
            }
            else -> null // NC, ND, proprietary or contradictory index metadata
        }
        return label?.takeIf(::allowedPortraitLicense)
    }
}
