package org.michimusic.core.intelligence

import java.security.MessageDigest
import java.util.Locale

/**
 * One canonical digest contract for the no-backup SQLite writer and MI-10 selector.
 * Digests are identifiers, NOT encryption, anonymization, or permission grants.
 */
object MichiTasteFingerprint {
    private const val MAX_ARTIST_LENGTH = 128
    private val hex = "0123456789abcdef".toCharArray()

    fun normalizeArtist(raw: String): String =
        if (raw.length <= MAX_ARTIST_LENGTH) raw.trim().lowercase(Locale.ROOT) else ""

    fun sha256(value: String): String {
        val data = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val chars = CharArray(data.size * 2)
        for (i in data.indices) {
            val b = data[i].toInt() and 0xff
            chars[i * 2] = hex[b ushr 4]
            chars[i * 2 + 1] = hex[b and 15]
        }
        return String(chars)
    }

    /** Length-prefixing prevents owner/track concatenation collisions. Empty local owner is valid. */
    fun ownedTrack(sourceId: String, trackId: String): String =
        sha256("${sourceId.length}:$sourceId${trackId.length}:$trackId")
}
