package org.michimusic.data.repository

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataFieldReadReport

/**
 * ME-01B. Maps only a FRESH embedded-tag read after the existing owner/URI/stamp
 * and cache equivalence gates pass. No MediaStore display values are promoted to
 * physical embedded tag evidence. The opaque token is NOT a write permit.
 *
 * The current reader recognizes a bounded subset of fields. A missing field must
 * remain UNVERIFIED unless the reader explicitly proves its absence. No parser
 * completeness or preservation of unknown binary tag frames is implied.
 */
internal object MetadataEditorEvidenceMapper {
    fun fromColdVerifiedRead(
        sourceId: String, trackId: String, uri: String,
        stamp: RecoveryDocumentStamp, fields: EmbeddedTagFields,
        report: MetadataFieldReadReport? = null,
    ): MetadataSnapshot {
        val known = linkedMapOf<MetadataField, List<String>>()
        fun text(field: MetadataField, value: String?) {
            value?.takeIf(String::isNotBlank)?.let { known[field] = listOf(it) }
        }
        fun numeric(field: MetadataField, value: Int?) {
            value?.takeIf { it in 1..9999 }?.let { known[field] = listOf(it.toString()) }
        }
        fun values(field: MetadataField, source: List<String>) {
            if (source.isNotEmpty()) known[field] = source.toList()
        }
        text(MetadataField.TITLE, fields.title)
        text(MetadataField.ARTIST, fields.artist)
        text(MetadataField.ALBUM, fields.album)
        text(MetadataField.ALBUM_ARTIST, fields.albumArtist)
        values(MetadataField.GENRE, fields.genres)
        text(MetadataField.COMPOSER, fields.composer)
        numeric(MetadataField.TRACK_NUMBER, fields.trackNumber)
        numeric(MetadataField.TRACK_TOTAL, fields.trackTotal)
        numeric(MetadataField.DISC_NUMBER, fields.discNumber)
        numeric(MetadataField.DISC_TOTAL, fields.discTotal)
        text(MetadataField.RELEASE_DATE, fields.date)
        values(MetadataField.MUSICBRAINZ_ARTIST_ID, fields.musicBrainzArtistIds)
        if (fields.musicBrainzRecordingIds.size == 1)
            values(MetadataField.MUSICBRAINZ_RECORDING_ID, fields.musicBrainzRecordingIds)
        if (fields.musicBrainzReleaseIds.size == 1)
            values(MetadataField.MUSICBRAINZ_RELEASE_ID, fields.musicBrainzReleaseIds)

        // Genre alone has an existing, separately verified NoGenreTag parser
        // distinction. Absent title/artist/credits are NOT proven empty, because
        // unsupported/flagged frames may have been skipped by bounded readers.
        val merged = MetadataEditorFieldEvidenceMerge.merge(known, report)
        val coverage = merged.verified
        val digest = MessageDigest.getInstance("SHA-256")
        val proofInput = buildString {
            append("ME04-READ\n")
            append(report?.container?.name ?: "LEGACY").append(':')
            append(report?.parserVersion ?: 0).append('\n')
            append(sourceId.length).append(':').append(sourceId).append('\n')
            append(trackId.length).append(':').append(trackId).append('\n')
            append(uri.length).append(':').append(uri).append('\n')
            append(stamp.size).append(':').append(stamp.modifiedAt).append('\n')
            for ((field, values) in merged.known) {
                append(field.name).append(':')
                for (value in values) append(value.length).append(':').append(value)
                append('\n')
            }
            for ((field, observation) in merged.observations) {
                append(field.name).append(':').append(observation.toString()).append('\n')
            }
        }
        val hash = digest.digest(proofInput.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return MetadataSnapshot(
            target = MetadataTarget(sourceId, trackId, uri, "me04:$hash"),
            tagsVerified = true,
            known = merged.known,
            // Not an assertion that unknown tag frames are absent! A future
            // writer must preserve the whole source, not reconstruct from here.
            opaque = emptyMap(),
            verifiedFields = coverage,
            fieldObservations = merged.observations,
        )
    }
}
