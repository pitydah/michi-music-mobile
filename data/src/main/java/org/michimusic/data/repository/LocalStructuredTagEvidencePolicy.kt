package org.michimusic.data.repository

/**
 * Trust boundary for metadata-only observations; no HTTP, Room or file access.
 * An empty result is meaningful: a successfully parsed file may have lost the MBID.
 * Never interpret ARTIST text or title as an identity, and never combine roles.
 */
object LocalStructuredTagEvidencePolicy {
    fun normalize(raw: LocalStructuredTagEvidence): LocalStructuredTagEvidence? {
        val id = raw.trackId.trim()
        if (id.isBlank() || id.length > 160 || id.any(Char::isISOControl)) return null
        val candidate = MusicBrainzTagIdPolicy.fromTags(mapOf(
            "MUSICBRAINZ_ARTISTID" to raw.artistMbids,
            "MUSICBRAINZ_ALBUMARTISTID" to raw.albumArtistMbids,
            "MUSICBRAINZ_TRACKID" to raw.recordingMbids,
            "MUSICBRAINZ_ALBUMID" to raw.releaseMbids,
        ))
        return LocalStructuredTagEvidence(
            trackId = id,
            artistMbids = candidate.artistIds,
            albumArtistMbids = candidate.albumArtistIds,
            recordingMbids = candidate.recordingIds,
            releaseMbids = candidate.releaseIds,
        )
    }
}
