package org.michimusic.mobile.artist.artwork

import java.net.URI
import java.util.Locale

/**
 * Commons imageinfo metadata is usable only for the FILE actually requested.
 * A successful API response (or P180 proof obtained separately) must not allow
 * another page's photo, licence, or attribution to be substituted.
 *
 * Old test fixtures may omit ns/title; a canonical Commons descriptionurl then
 * acts as a secondary file-binding receipt. Production Commons replies supply both.
 */
internal object CommonsFileSourcePolicy {
    fun matchesRequestedFile(
        requested: String,
        returnedTitle: String?,
        returnedNamespace: String?,
        descriptionUrl: String?,
    ): Boolean {
        val expected = fileKey(requested) ?: return false
        if (returnedNamespace != null && returnedNamespace != "6") return false
        if (returnedTitle != null) {
            if (!returnedTitle.startsWith("File:") ||
                fileKey(returnedTitle.removePrefix("File:")) != expected) return false
        }
        if (descriptionUrl != null) {
            val uri = try { URI(descriptionUrl) } catch (_: Exception) { return false }
            if (uri.scheme != "https" || !uri.host.equals("commons.wikimedia.org", true) ||
                uri.port != -1 || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null) return false
            val path = uri.path.orEmpty()
            if (!path.startsWith("/wiki/File:") ||
                fileKey(path.removePrefix("/wiki/File:")) != expected) return false
        }
        // Never bind an unattributed anonymous page when the response contains
        // neither a title nor a canonical source URL.
        return returnedTitle != null || descriptionUrl != null
    }

    private fun fileKey(value: String): String? {
        if (value.isBlank() || value.length > 180 || value.any { ch ->
            ch.isISOControl() || ch in "/\\|?#<>[]{}"
        }) return null
        return value.replace('_', ' ').trim().lowercase(Locale.ROOT)
            .takeIf(String::isNotBlank)
    }
}
