package org.michimusic.data.cache

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    /** Single, owner-scoped read for fast foreground MediaStore indexing. */
    @Query("SELECT * FROM embedded_tag_evidence WHERE sourceId = :sourceId")
    suspend fun getTagEvidenceForSource(sourceId: String): List<EmbeddedTagEvidence>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTagEvidence(rows: List<EmbeddedTagEvidence>)

    @Query("DELETE FROM embedded_tag_evidence WHERE sourceId = :sourceId")
    suspend fun deleteTagEvidenceForSource(sourceId: String)

    /** Index + structured evidence must change together, never partially. */
    @Transaction
    suspend fun replaceForSourceWithEvidence(
        sourceId: String,
        tracks: List<CachedTrack>,
        evidence: List<EmbeddedTagEvidence>,
    ) {
        deleteForSource(sourceId)
        deleteTagEvidenceForSource(sourceId)
        if (tracks.isNotEmpty()) insertAll(tracks)
        if (evidence.isNotEmpty()) putTagEvidence(evidence)
    }

    /** Removing a SAF library must also remove its orphanable evidence. */
    @Transaction
    suspend fun clearSourceWithEvidence(sourceId: String) {
        deleteForSource(sourceId)
        deleteTagEvidenceForSource(sourceId)
    }

    /** Updates only missing genres; guard prevents racing with a changed SAF document. */
    @Query(
        "UPDATE cached_tracks SET genre = :genre WHERE sourceId = :sourceId " +
            "AND id = :id AND filepath = :filepath AND size = :size AND (TRIM(genre) = '' OR LOWER(TRIM(genre)) IN ('unknown','unknown genre','<unknown>','sin género','sin genero','sin clasificar','n/a','unspecified','not specified','?'))",
    )
    suspend fun fillMissingSafGenre(
        sourceId: String, id: String, filepath: String, size: Long, genre: String,
    ): Int

    @Transaction
    suspend fun persistRecoveredGenres(
        evidence: List<EmbeddedTagEvidence>,
        safChanges: List<RecoveredSafGenre>,
    ) {
        val rejected = HashSet<Pair<String, String>>()
        for (change in safChanges) {
            val changed = fillMissingSafGenre(
                change.sourceId, change.trackId, change.filepath, change.size, change.genre,
            )
            if (changed == 0) rejected.add(change.sourceId to change.trackId)
        }
        val allowed = evidence.filterNot { (it.sourceId to it.trackId) in rejected }
        if (allowed.isNotEmpty()) putTagEvidence(allowed)
    }

    @Query("SELECT * FROM cached_tracks ORDER BY title")
    fun getAllTracks(): Flow<List<CachedTrack>>

    @Query("SELECT * FROM cached_tracks ORDER BY title")
    fun getAllTracksPagingSource(): PagingSource<Int, CachedTrack>

    /** All owners of an ID; callers must fail closed when more than one exists. */
    @Query("SELECT * FROM cached_tracks WHERE id = :id ORDER BY sourceId")
    suspend fun getTracksById(id: String): List<CachedTrack>

    // K16-01: a track ID is unique only inside one library. Callers that already
    // know the owner must query with both halves of the composite key.
    @Query("SELECT * FROM cached_tracks WHERE sourceId = :sourceId AND id = :id")
    suspend fun getTrackBySource(sourceId: String, id: String): CachedTrack?

    @Query("SELECT * FROM cached_tracks WHERE sourceId = :sourceId ORDER BY title")
    fun getTracksForSource(sourceId: String): Flow<List<CachedTrack>>

    @Query("DELETE FROM cached_tracks WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    /** Atomically replaces one root's index; a failed insert rolls back the delete. */
    @Transaction
    suspend fun replaceForSource(sourceId: String, tracks: List<CachedTrack>) {
        deleteForSource(sourceId)
        if (tracks.isNotEmpty()) insertAll(tracks)
    }

    @Query("SELECT * FROM cached_tracks WHERE downloaded = 1")
    fun getDownloadedTracks(): Flow<List<CachedTrack>>

    @Query("SELECT * FROM cached_tracks WHERE downloaded = 1 AND sourceId = :sourceId")
    fun getDownloadedTracksForSource(sourceId: String): Flow<List<CachedTrack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<CachedTrack>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: CachedTrack)

    @Delete
    suspend fun delete(track: CachedTrack)

    @Query("DELETE FROM cached_tracks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_tracks")
    suspend fun count(): Int

    @Query("SELECT * FROM cached_tracks WHERE downloaded = 0")
    suspend fun getUndownloaded(): List<CachedTrack>

    /** Cold genre evidence: active only while the Genres route is composed. */
    @Query(
        "SELECT sourceId,id,title,artist,album,duration,filepath,genre " +
            "FROM cached_tracks ORDER BY sourceId,id",
    )
    fun observeGenreEvidenceSnapshot(): Flow<List<CachedGenreMetadata>>

    @Query("UPDATE cached_tracks SET downloaded = 1 WHERE sourceId = :sourceId AND id = :id")
    suspend fun markDownloaded(sourceId: String, id: String)

    // A path alone is not verification: clear any previous proof when a caller
    // only records location.
    @Query("UPDATE cached_tracks SET downloaded = 1, filepath = :filepath, verifiedSize = 0, verifiedSha256 = '', verifiedAt = 0, verifiedAlgorithm = '' WHERE sourceId = :sourceId AND id = :id")
    suspend fun markDownloadedWithPath(sourceId: String, id: String, filepath: String)

    @Query(
        "UPDATE cached_tracks SET downloaded = 1, filepath = :filepath, verifiedSize = :size, " +
            "verifiedSha256 = :sha256, verifiedAt = :verifiedAt, verifiedAlgorithm = :algorithm " +
            "WHERE sourceId = :sourceId AND id = :id",
    )
    suspend fun markDownloadedVerified(
        sourceId: String,
        id: String,
        filepath: String,
        size: Long,
        sha256: String,
        verifiedAt: Long,
        algorithm: String,
    )
}
