package org.michimusic.data.repository

import android.net.Uri
import android.provider.MediaStore
import org.michimusic.core.models.Track
import org.michimusic.data.cache.LibraryExclusionEntity
import java.security.MessageDigest

/** Source-scoped; SAF tree identity comes from provider authority and tree document ID. */
fun libraryVisibilitySource(root: MusicRoot): String = if (root.isDefault) {
    "mediastore:${MediaStore.VOLUME_EXTERNAL_PRIMARY}:Music"
} else {
    root.sourceId?.takeIf { it.isNotBlank() }
        ?: root.treeUri?.let { customRootSourceId(Uri.parse(it)) }
        ?: "invalid-root"
}

/** An ID recycled by MediaStore/SAF must not hide an unrelated replacement file.
 * Metadata tags (title/artist/album) are purposefully excluded: retagging keeps the
 * same exclusion when the observed file identity, size and timing remain stable.
 */
fun libraryVisibilityFingerprint(track: Track): String {
    val canonical = listOf(track.filepath, track.size.toString(),
        track.duration.toString(), track.dateAdded.toString()).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun isLibraryTrackHidden(
    entry: LibraryExclusionEntity?, source: String, track: Track,
): Boolean = entry != null && entry.sourceId == source && entry.trackId == track.id &&
    entry.reason == "USER_HIDDEN" && entry.schemaVersion == 1 &&
    entry.guardFingerprint == libraryVisibilityFingerprint(track)

/** Expected snapshot, never an instruction to modify a physical URI. */
data class LocalVisibilitySelection(
    val trackId: String,
    val uri: String,
    val fingerprint: String,
) {
    companion object {
        fun capture(track: Track) = LocalVisibilitySelection(
            trackId = track.id,
            uri = track.filepath,
            fingerprint = libraryVisibilityFingerprint(track),
        )
    }
}

sealed interface LocalVisibilityBatchResult {
    data class Hidden(val count: Int) : LocalVisibilityBatchResult
    data class Rejected(val message: String) : LocalVisibilityBatchResult
}

sealed interface LocalVisibilityResult {
    data object Hidden : LocalVisibilityResult
    data object Restored : LocalVisibilityResult
    data class Rejected(val message: String) : LocalVisibilityResult
}
