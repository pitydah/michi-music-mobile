package org.michimusic.data.repository

import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.models.Track
import org.michimusic.data.cache.MichiDatabase

/**
 * Applies a verified post-commit notification to one in-memory catalog row.
 * This is presentation projection only; Room's exact applied marker remains authority.
 *
 * One committed revision may mix fields these lists display with fields they do
 * not show (composer, lyrics, MusicBrainz ids, artwork-only resources). Only the
 * projectable subset is written here: an extended field is never a reason to
 * discard a revision the source already committed.
 */
object MetadataCommittedTrackProjection {
    sealed interface Result {
        data class Applied(val track: Track) : Result
        data class Refused(val reason: String) : Result
    }

    /** Scalar fields the local catalog displays and can patch without a provider re-read. */
    val projectableFields: Set<MetadataField> = setOf(
        MetadataField.TITLE,
        MetadataField.ARTIST,
        MetadataField.ALBUM,
        MetadataField.ALBUM_ARTIST,
        MetadataField.GENRE,
        MetadataField.TRACK_NUMBER,
        MetadataField.RELEASE_DATE,
    )

    /** Verified fields the workbench may write but these catalog lists never project. */
    val extendedFields: Set<MetadataField> =
        MetadataField.entries.filterNot { it in projectableFields }.toSet()

    fun apply(track: Track, change: MetadataCommittedLibraryChange): Result {
        val sourceMatches = track.sourceId == change.sourceId ||
            (track.sourceId.isBlank() &&
                change.sourceId == MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID)
        if (!sourceMatches || track.id != change.trackId || track.filepath != change.uri) {
            return Result.Refused("TARGET_MISMATCH")
        }
        if (!change.snapshot.tagsVerified) return Result.Refused("SNAPSHOT_UNVERIFIED")

        // A mixed revision projects its projectable subset and keeps the rest untouched.
        val projectedFields = change.changedFields intersect projectableFields

        fun text(field: MetadataField, current: String): String? {
            if (field !in projectedFields) return current
            val values = change.snapshot.known[field] ?: return null
            // Scalar Room columns display a deterministic primary value; the exact
            // multivalue set remains in the source and in structured evidence.
            return values.firstOrNull().orEmpty()
        }

        fun number(field: MetadataField, current: Int): Int? {
            if (field !in projectedFields) return current
            val values = change.snapshot.known[field] ?: return null
            if (values.isEmpty()) return 0
            if (values.size != 1 || !values.single().matches(Regex("[0-9]{1,4}"))) return null
            return values.single().toIntOrNull()?.takeIf { it in 1..9999 }
        }

        val year = if (MetadataField.RELEASE_DATE !in projectedFields) {
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
                albumId = if (MetadataField.ALBUM in projectedFields ||
                    MetadataField.ALBUM_ARTIST in projectedFields
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
