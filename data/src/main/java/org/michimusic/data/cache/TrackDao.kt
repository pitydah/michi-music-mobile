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

    /** Cold lookup restricted to one manually verified page. */
    @Query("SELECT * FROM embedded_tag_evidence WHERE sourceId = :sourceId AND trackId IN (:trackIds)")
    suspend fun getTagEvidenceForTracks(sourceId: String, trackIds: List<String>): List<EmbeddedTagEvidence>

    @Query("SELECT * FROM embedded_tag_evidence WHERE sourceId = :sourceId AND trackId = :trackId LIMIT 1")
    suspend fun getTagEvidenceForTrack(sourceId: String, trackId: String): EmbeddedTagEvidence?

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
            "AND id = :id AND filepath = :filepath AND size = :size AND (TRIM(genre) = '' OR LOWER(TRIM(genre)) IN ('unknown','unknown genre','<unknown>','sin género','sin genero','sin clasificar','none','unclassified','n/a','unspecified','not specified','?')) " +
            "AND EXISTS (SELECT 1 FROM embedded_tag_evidence AS e WHERE e.sourceId = :sourceId " +
            "AND e.trackId = :id AND e.sizeBytes = :size AND e.modifiedAt = :expectedModifiedAt " +
            "AND e.modifiedAt > 0 AND e.genre = :genre AND e.parserVersion = :parserVersion)",
    )
    suspend fun fillMissingSafGenre(
        sourceId: String, id: String, filepath: String, size: Long, genre: String,
        expectedModifiedAt: Long,
        parserVersion: Int = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
    ): Int

    @Transaction
    suspend fun persistRecoveredGenres(
        evidence: List<EmbeddedTagEvidence>,
        safChanges: List<RecoveredSafGenre>,
    ): Set<Pair<String, String>> {
        val applied = HashSet<Pair<String, String>>()
        val requested = HashSet<Pair<String, String>>()
        val proposals = evidence.groupBy { it.sourceId to it.trackId }
        for (change in safChanges) {
            val key = change.sourceId to change.trackId
            // Duplicate/ambiguous payloads fail closed; never commit a partial page.
            if (!requested.add(key)) throw IllegalArgumentException("Duplicate SAF recovery key")
            val candidate = proposals[key]?.singleOrNull()
            if (candidate == null || candidate.modifiedAt <= 0L ||
                candidate.modifiedAt != change.expectedModifiedAt ||
                candidate.sizeBytes != change.size || candidate.genre != change.genre ||
                candidate.parserVersion != EmbeddedTagEvidence.CURRENT_PARSER_VERSION ||
                change.genre.isBlank()) continue
            val indexed = getTrackBySource(change.sourceId, change.trackId)
            if (indexed == null || indexed.filepath != change.filepath ||
                indexed.size != change.size) continue
            val previous = getTagEvidenceForTrack(change.sourceId, change.trackId)
            // A pre-existing indexed stamp is authoritative; a changed stamp requires
            // an explicit reindex, not a silent overwrite from a recovered file.
            if (previous != null && (previous.sizeBytes != candidate.sizeBytes ||
                previous.modifiedAt != candidate.modifiedAt ||
                previous.parserVersion != candidate.parserVersion)) continue
            // This UPSERT and the guarded UPDATE share the Room transaction.
            // The old implementation checked EXISTS before the new evidence was saved,
            // making first-time SAF genre recovery impossible without a prior sidecar.
            putTagEvidence(listOf(candidate))
            val changed = fillMissingSafGenre(
                change.sourceId, change.trackId, change.filepath, change.size, change.genre,
                change.expectedModifiedAt, EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
            )
            // Unexpected rejection must roll back the UPSERT as well; no ghost sidecar.
            if (changed != 1) throw IllegalStateException("SAF index changed during recovery")
            applied.add(key)
        }
        // Non-mutating metadata reads may still populate cold evidence, but never
        // overwrite a rejected recovery candidate or cross a different owner/ID.
        val ordinary = evidence.filter { (it.sourceId to it.trackId) !in requested }
        val safeOrdinary = ordinary.filter { candidate ->
            if (candidate.modifiedAt <= 0 ||
                candidate.parserVersion != EmbeddedTagEvidence.CURRENT_PARSER_VERSION) false
            else if (candidate.sourceId.isEmpty()) true // MediaStore has no cached SAF index row.
            else {
                val indexed = getTrackBySource(candidate.sourceId, candidate.trackId)
                val previous = getTagEvidenceForTrack(candidate.sourceId, candidate.trackId)
                indexed != null && indexed.size == candidate.sizeBytes && previous != null &&
                    previous.sizeBytes == candidate.sizeBytes &&
                    previous.modifiedAt == candidate.modifiedAt &&
                    previous.parserVersion == candidate.parserVersion
            }
        }
        if (safeOrdinary.isNotEmpty()) putTagEvidence(safeOrdinary)
        return applied
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

    /**
     * One owner-aware snapshot. The left join is 1:1 because the sidecar primary
     * key is (sourceId,trackId). Never trust structured values from a stale size,
     * parser version or a sidecar whose genre disagrees with the cached row.
     * No new file opens or database polling is introduced by this query.
     */
    @Query(
        "SELECT t.sourceId,t.id,t.title,t.artist,t.album,t.duration,t.filepath,t.genre," +
            " CASE WHEN e.parserVersion = :parserVersion AND e.sizeBytes = t.size" +
            " AND e.modifiedAt > 0 AND e.genre = t.genre THEN e.genreValues ELSE '' END AS genreValues" +
            " FROM cached_tracks AS t" +
            " LEFT JOIN embedded_tag_evidence AS e ON e.sourceId = t.sourceId" +
            " AND e.trackId = t.id ORDER BY t.sourceId,t.id",
    )
    fun observeGenreEvidenceSnapshot(
        parserVersion: Int = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
    ): Flow<List<CachedGenreMetadata>>

    /** Cold owner-scoped metadata lookup, never a whole-library file scan. */
    @Query(
        "SELECT e.* FROM embedded_tag_evidence AS e" +
            " INNER JOIN cached_tracks AS t ON t.sourceId = e.sourceId AND t.id = e.trackId" +
            " WHERE e.sourceId = :sourceId AND e.trackId = :trackId" +
            " AND e.parserVersion = :parserVersion AND e.sizeBytes = t.size" +
            " AND e.modifiedAt > 0 AND e.genre = t.genre LIMIT 1",
    )
    suspend fun getCurrentExtendedTagEvidence(
        sourceId: String, trackId: String,
        parserVersion: Int = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
    ): EmbeddedTagEvidence?

    /** User-opened details require the exact indexed location, not merely owner+id. */
    @Query(
        "SELECT e.* FROM embedded_tag_evidence AS e" +
            " INNER JOIN cached_tracks AS t ON t.sourceId = e.sourceId AND t.id = e.trackId" +
            " WHERE e.sourceId = :sourceId AND e.trackId = :trackId" +
            " AND t.filepath = :filepath AND t.size = :expectedSize" +
            " AND e.parserVersion = :parserVersion AND e.sizeBytes = t.size" +
            " AND e.modifiedAt > 0 AND e.genre = t.genre LIMIT 1",
    )
    suspend fun getCurrentExtendedTagEvidenceForPath(
        sourceId: String, trackId: String, filepath: String, expectedSize: Long,
        parserVersion: Int = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
    ): EmbeddedTagEvidence?

    /** Bounded batched detail lookup: no one-query-per-track N+1 behavior. */
    @Query(
        "SELECT e.* FROM embedded_tag_evidence AS e" +
            " INNER JOIN cached_tracks AS t ON t.sourceId = e.sourceId AND t.id = e.trackId" +
            " WHERE e.sourceId = :sourceId AND e.trackId IN (:trackIds)" +
            " AND e.parserVersion = :parserVersion AND e.sizeBytes = t.size" +
            " AND e.modifiedAt > 0 AND e.genre = t.genre ORDER BY e.trackId",
    )
    suspend fun getCurrentExtendedTagEvidenceBatch(
        sourceId: String, trackIds: List<String>,
        parserVersion: Int = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
    ): List<EmbeddedTagEvidence>

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
