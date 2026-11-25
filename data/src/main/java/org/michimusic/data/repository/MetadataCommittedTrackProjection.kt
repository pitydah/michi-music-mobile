package org.michimusic.data.repository

import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.models.Track
import org.michimusic.data.cache.MichiDatabase

/**
 * Applies a verified post-commit notification to one in-memory catalog row.
 * This is presentation projection only; Room's exact applied marker remains authority.
 */
object MetadataCommittedTrackProjection {
    sealed interface Result {
        data class Applied(val track: Track) : Result
        data class Refused(val reason: String) : Result
    }

    fun apply(track: Track, change: MetadataCommittedLibraryChange): Result {
        val sourceMatches = track.sourceId == change.sourceId ||
            (track.sourceId.isBlank() &&
                change.sourceId == MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID)
        if (!sourceMatches || track.id != change.trackId || track.filepath != change.uri) {
            return Result.Refused("TARGET_MISMATCH")
        }
        if (!change.snapshot.tagsVerified) return Result.Refused("SNAPSHOT_UNVERIFIED")

        val supported = setOf(
            MetadataField.TITLE,
            MetadataField.ARTIST,
            MetadataField.ALBUM,
            MetadataField.ALBUM_ARTIST,
            MetadataField.GENRE,
            MetadataField.TRACK_NUMBER,
            MetadataField.RELEASE_DATE,
        )
        if (!supported.containsAll(change.changedFields)) {
            return Result.Refused("FIELD_NOT_PROJECTABLE")
        }

        fun text(field: MetadataField, current: String): String? {
            if (field !in change.changedFields) return current
            val values = change.snapshot.known[field] ?: return null
            return if (values.size <= 1) values.firstOrNull().orEmpty() else null
        }

        fun number(field: MetadataField, current: Int): Int? {
            if (field !in change.changedFields) return current
            val values = change.snapshot.known[field] ?: return null
            if (values.isEmpty()) return 0
            if (values.size != 1 || !values.single().matches(Regex("[0-9]{1,4}"))) return null
            return values.single().toIntOrNull()?.takeIf { it in 1..9999 }
        }

        val year = if (MetadataField.RELEASE_DATE !in change.changedFields) {
            track.year
        } else {
            val values = change.snapshot.known[MetadataField.RELEASE_DATE]
                ?: return Result.Refused("FIELD_NOT_OBSERVED")
            when {
                values.isEmpty() -> 0
                values.size == 1 &&
                    values.single().matches(Regex("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?")) ->
                    values.single().take(4).toIntOrNull()
                        ?: return Result.Refused("INVALID_YEAR")
                else -> return Result.Refused("INVALID_YEAR")
            }
        }

        val title = text(MetadataField.TITLE, track.title)
            ?: return Result.Refused("INVALID_TITLE")
        val artist = text(MetadataField.ARTIST, track.artist)
            ?: return Result.Refused("INVALID_ARTIST")
        val album = text(MetadataField.ALBUM, track.album)
            ?: return Result.Refused("INVALID_ALBUM")
        val albumArtist = text(MetadataField.ALBUM_ARTIST, track.albumArtist)
            ?: return Result.Refused("INVALID_ALBUM_ARTIST")
        val genre = text(MetadataField.GENRE, track.genre)
            ?: return Result.Refused("INVALID_GENRE")
        val trackNumber = number(MetadataField.TRACK_NUMBER, track.trackNumber)
            ?: return Result.Refused("INVALID_TRACK_NUMBER")

        return Result.Applied(
            track.copy(
                title = title,
                artist = artist,
                album = album,
                albumId = if (MetadataField.ALBUM in change.changedFields ||
                    MetadataField.ALBUM_ARTIST in change.changedFields
                ) "" else track.albumId,
                albumArtist = albumArtist,
                genre = genre,
                trackNumber = trackNumber,
                year = year,
                size = change.committedBytes,
                contentHash = change.committedSha256,
            ),
        )
    }
}
