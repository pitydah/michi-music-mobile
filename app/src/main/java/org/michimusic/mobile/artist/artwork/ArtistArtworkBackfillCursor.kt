package org.michimusic.mobile.artist.artwork

import java.security.MessageDigest

/** Persisted keyset cursor key: source-specific and contains no user path text. */
internal object ArtistArtworkBackfillCursor {
    fun key(libraryId: String, sourceId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$libraryId\u0000$sourceId".toByteArray(Charsets.UTF_8))
        return "cursor_" + bytes.take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
