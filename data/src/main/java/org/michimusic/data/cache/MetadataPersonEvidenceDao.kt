package org.michimusic.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Owner-aware sidecar access. These are cache reads; never a source-file scan. */
@Dao
interface MetadataPersonEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(evidence: MetadataPersonEvidence)

    @Query("DELETE FROM metadata_people_evidence WHERE sourceId=:sourceId AND trackId=:trackId")
    suspend fun deleteForTrack(sourceId: String, trackId: String): Int

    /** Never read evidence from another owner or a stale indexed size. */
    @Query("SELECT p.* FROM metadata_people_evidence AS p " +
        "INNER JOIN cached_tracks AS t ON t.sourceId=p.sourceId AND t.id=p.trackId " +
        "WHERE p.sourceId=:sourceId AND p.trackId=:trackId " +
        "AND p.sizeBytes=t.size AND p.modifiedAt>0 LIMIT 1")
    suspend fun currentForTrack(sourceId: String, trackId: String): MetadataPersonEvidence?

    /** One bounded observation for an opened album; never the whole library.
     * The joined indexedPath, indexedSize and scalars are rechecked by policy. */
    @Query("SELECT p.*,t.filepath AS indexedPath,t.size AS indexedSize," +
        "t.artist AS indexedArtist,t.albumArtist AS indexedAlbumArtist " +
        "FROM metadata_people_evidence AS p INNER JOIN cached_tracks AS t " +
        "ON t.sourceId=p.sourceId AND t.id=p.trackId " +
        "WHERE p.sourceId=:sourceId AND p.trackId IN (:trackIds) " +
        "AND p.sizeBytes=t.size AND p.modifiedAt>0 ORDER BY p.trackId")
    fun observeIndexedForAlbum(
        sourceId: String, trackIds: List<String>,
    ): Flow<List<MetadataPeopleIndexedRow>>

    /** No observer for off-screen tracks or remote sessions. */
    @Query("SELECT p.*,t.filepath AS indexedPath,t.size AS indexedSize," +
        "t.artist AS indexedArtist,t.albumArtist AS indexedAlbumArtist " +
        "FROM metadata_people_evidence AS p INNER JOIN cached_tracks AS t " +
        "ON t.sourceId=p.sourceId AND t.id=p.trackId " +
        "WHERE p.sourceId=:sourceId AND p.trackId=:trackId " +
        "AND p.sizeBytes=t.size AND p.modifiedAt>0 LIMIT 1")
    fun observeIndexedForTrack(
        sourceId: String, trackId: String,
    ): Flow<MetadataPeopleIndexedRow?>

    /** Observed only by an explicitly opened metadata details panel. */
    @Query("SELECT p.* FROM metadata_people_evidence AS p " +
        "INNER JOIN cached_tracks AS t ON t.sourceId=p.sourceId AND t.id=p.trackId " +
        "WHERE p.sourceId=:sourceId AND p.trackId=:trackId " +
        "AND p.sizeBytes=t.size AND p.modifiedAt>0 LIMIT 1")
    fun observeForTrack(sourceId: String, trackId: String): Flow<MetadataPersonEvidence?>
}
