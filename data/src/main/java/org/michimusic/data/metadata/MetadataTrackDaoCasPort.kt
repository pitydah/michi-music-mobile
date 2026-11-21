package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataUnverifiedIndexRowPolicy
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao

/**
 * ME-11A: real :data -> existing TrackDao bridge, but ONLY for rows with
 * a preexisting verified SHA and durable verifiedAt identity. No synthetic
 * Row version or guessed hashes. Other library rows fail closed and require
 * normal reindex when the writer is eventually certified.
 *
 * The DAO compare-and-swap is a single Room transaction with a conditional
 * update of ALL source-facing cached scalar fields and deletion of the stale
 * embedded-tag sidecar. It never invents multivalue scalar flattening.
 */
internal class MetadataTrackDaoCasPort(private val dao: TrackDao) : MetadataIndexCasCoordinator.Port {
    private val hex=Regex("[a-f0-9]{64}")

    override suspend fun current(targetSource: String,targetTrack: String):
        MetadataIndexPublicationPolicy.IndexIdentity? {
        val row=dao.getTrackBySource(targetSource,targetTrack) ?: return null
        return when {
            supportedRow(row) -> MetadataIndexPublicationPolicy.IndexIdentity(row.sourceId,
                row.id,row.filepath,row.verifiedAt,row.verifiedSha256)
            missingHistoricProof(row) -> MetadataIndexPublicationPolicy.IndexIdentity(row.sourceId,
                row.id,row.filepath,0L,"")
            else -> null
        }
    }

    private fun supportedRow(row: CachedTrack): Boolean =
        row.filepath.startsWith("content://") && row.verifiedAt > 0L &&
        row.verifiedSize == row.size && row.size > 0L &&
        row.verifiedAlgorithm.lowercase() == "sha256" && hex.matches(row.verifiedSha256)

    private fun missingHistoricProof(row: CachedTrack): Boolean =
        row.filepath.startsWith("content://") && row.size in 12..(1024L*1024*1024) &&
            row.verifiedSize == 0L && row.verifiedAt == 0L &&
            row.verifiedSha256.isEmpty() && row.verifiedAlgorithm.isEmpty()

    override suspend fun updateIfVersion(expected: MetadataIndexPublicationPolicy.IndexIdentity,
        delta: MetadataSourceIndexDelta.Delta): Boolean {
        if (!MetadataCommitCertification.mayMutateOriginal()) return false
        if (expected.sourceId != delta.target.sourceId ||
            expected.trackId != delta.target.trackId || expected.uri != delta.target.uri ||
            !hex.matches(delta.sourceSha256) || delta.candidateBytes !in 12..(1024L*1024*1024))
            return false
        val row=dao.getTrackBySource(expected.sourceId,expected.trackId) ?: return false
        if (row.filepath != expected.uri) return false
        val candidate=MetadataTrackIndexProjection.propose(row,delta) ?: return false
        if (missingHistoricProof(row)) {
            // source origin proof is not stored in Room: the journal exact
            // original SHA must be revalidated by the caller. The port only
            // accepts the empty-index identity and never invents a hash.
            if (expected.version != 0L || expected.observedSourceSha256.isNotEmpty()) return false
            return dao.commitMetadataAfterVerifiedWriteUnhashed(
                sourceId=row.sourceId,trackId=row.id,filepath=row.filepath,
                previousSize=row.size,previousTitle=row.title,previousArtist=row.artist,
                previousAlbum=row.album,previousAlbumArtist=row.albumArtist,
                previousGenre=row.genre,previousTrackNumber=row.trackNumber,
                previousYear=row.year,newSize=delta.candidateBytes,
                newTitle=candidate.title,newArtist=candidate.artist,
                newAlbum=candidate.album,newAlbumArtist=candidate.albumArtist,
                newGenre=candidate.genre,newTrackNumber=candidate.trackNumber,
                newYear=candidate.year,
            )
        }
        if (!supportedRow(row) || row.verifiedAt != expected.version ||
            row.verifiedSha256 != expected.observedSourceSha256) return false
        return dao.commitMetadataAfterVerifiedWrite(
            sourceId=row.sourceId,trackId=row.id,filepath=row.filepath,
            previousSize=row.size,previousVerifiedAt=row.verifiedAt,
            previousSha256=row.verifiedSha256,
            previousTitle=row.title,previousArtist=row.artist,previousAlbum=row.album,
            previousAlbumArtist=row.albumArtist,previousGenre=row.genre,
            previousTrackNumber=row.trackNumber,previousYear=row.year,
            newSize=delta.candidateBytes,newTitle=candidate.title,
            newArtist=candidate.artist,newAlbum=candidate.album,
            newAlbumArtist=candidate.albumArtist,newGenre=candidate.genre,
            newTrackNumber=candidate.trackNumber,newYear=candidate.year,
        )
    }
}

/** Exact scalar projection. Multi-value evidence is never joined with ";" or "/". */
internal object MetadataTrackIndexProjection {
    data class Fields(val title:String,val artist:String,val album:String,
        val albumArtist:String,val genre:String,val trackNumber:Int,val year:Int)
    fun propose(row:CachedTrack,delta:MetadataSourceIndexDelta.Delta):Fields? {
        if (delta.target.sourceId != row.sourceId || delta.target.trackId != row.id ||
            delta.target.uri != row.filepath) return null
        val values=delta.observedValues
        fun single(field:MetadataField,current:String):String? {
            val candidate=values[field] ?: return current
            return if (candidate.size <= 1) candidate.firstOrNull().orEmpty() else null
        }
        fun number(field:MetadataField,current:Int):Int? {
            val candidate=values[field] ?: return current
            if (candidate.isEmpty()) return 0
            if (candidate.size != 1 || !candidate[0].matches(Regex("[0-9]{1,4}"))) return null
            return candidate[0].toIntOrNull()?.takeIf { it in 1..9999 }
        }
        val year=values[MetadataField.RELEASE_DATE]?.let { date ->
            if (date.isEmpty()) 0
            else if (date.size == 1 && date[0].matches(Regex("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?")))
                date[0].take(4).toIntOrNull()
            else null
        } ?: if (MetadataField.RELEASE_DATE !in values) row.year else return null
        return Fields(
            single(MetadataField.TITLE,row.title) ?: return null,
            single(MetadataField.ARTIST,row.artist) ?: return null,
            single(MetadataField.ALBUM,row.album) ?: return null,
            single(MetadataField.ALBUM_ARTIST,row.albumArtist) ?: return null,
            single(MetadataField.GENRE,row.genre) ?: return null,
            number(MetadataField.TRACK_NUMBER,row.trackNumber) ?: return null,
            year)
    }
}
