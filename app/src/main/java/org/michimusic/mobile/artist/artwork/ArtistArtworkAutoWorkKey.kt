package org.michimusic.mobile.artist.artwork

import java.security.MessageDigest
import java.util.Locale

/** Deterministic, privacy-preserving job key. A credit is NOT an artist identity. */
internal object ArtistArtworkAutoWorkKey {
    fun creditKey(credit: String): String? = credit.trim().lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() && it.length <= 120 && it.none(Char::isISOControl) }

    fun uniqueName(libraryId: String, creditKey: String, associationId: String, sourceFingerprint: String): String? {
        // Delimiter-based serialization is injective only when each component
        // forbids control characters, including NUL in libraryId.
        if (libraryId.isBlank() || libraryId == "SCOPE_ALL" || libraryId.length > 160 ||
            libraryId.any(Char::isISOControl) ||
            creditKey(creditKey) != creditKey || associationId.isBlank() || associationId.length > 160 ||
            associationId.any(Char::isISOControl) ||
            !sourceFingerprint.matches(Regex("[0-9a-f]{64}"))
        ) return null
        val input = "$libraryId\u0000$creditKey\u0000$associationId\u0000$sourceFingerprint"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "michi.artist.auto.$hash"
    }
}
