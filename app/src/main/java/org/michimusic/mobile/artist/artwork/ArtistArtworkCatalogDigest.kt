package org.michimusic.mobile.artist.artwork

import org.michimusic.data.repository.LocalCatalogRow
import java.security.MessageDigest
import java.util.Locale

/** Pure incremental plan: deterministic, namespace-safe, never reads a media file. */
internal data class ArtistArtworkCatalogDigestPlan(
    val digest: String,
    val allTrackHashes: Set<String>,
    val added: List<LocalCatalogRow>,
)

internal object ArtistArtworkCatalogDigest {
    private fun sha256(value: String, digest: MessageDigest): ByteArray =
        digest.digest(value.toByteArray(Charsets.UTF_8))

    private fun hexPrefix(bytes: ByteArray): String = bytes.take(12).joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

    fun trackHash(sourceId: String, trackId: String): String =
        hexPrefix(sha256("$sourceId\u0000$trackId", MessageDigest.getInstance("SHA-256")))

    /** Row identity is source+track+album+normalized credit; a tag correction is a delta. */
    fun rowHash(sourceId: String, row: LocalCatalogRow): String =
        rowHash(sourceId, row, MessageDigest.getInstance("SHA-256"))

    private fun rowHash(sourceId: String, row: LocalCatalogRow, digest: MessageDigest): String {
        val credit = row.creditedArtist.trim().lowercase(Locale.ROOT)
        val album = row.albumKey.trim()
        val value = "$sourceId\u0000${row.trackId}\u0000$album\u0000$credit"
        return hexPrefix(sha256(value, digest))
    }

    fun plan(
        sourceId: String,
        rows: Collection<LocalCatalogRow>,
        previousHashes: Set<String>,
    ): ArtistArtworkCatalogDigestPlan {
        // Duplicate identical credits on an imported track are represented once. A
        // corrected artist or album changes the row hash even if trackId is stable.
        // One SHA-256 instance per plan, reused sequentially; no shared global
        // digest and no 100k per-row provider allocations on large libraries.
        val digestEngine = MessageDigest.getInstance("SHA-256")
        val current = linkedMapOf<String, LocalCatalogRow>()
        rows.forEach { row ->
            if (row.trackId.isNotBlank() && row.albumKey.isNotBlank() && row.creditedArtist.isNotBlank()) {
                current.putIfAbsent(rowHash(sourceId, row, digestEngine), row)
            }
        }
        val hashes = current.keys.toSet()
        val digest = sha256(hashes.sorted().joinToString("\n"), digestEngine)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val added = current.asSequence()
            .filter { (hash, _) -> hash !in previousHashes }
            .map { it.value }
            .toList()
        return ArtistArtworkCatalogDigestPlan(digest, hashes, added)
    }
}
