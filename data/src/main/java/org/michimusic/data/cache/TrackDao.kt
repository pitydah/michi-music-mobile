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
            "AND id = :id AND filepath = :filepath AND size = :size AND (" +
            org.michimusic.data.cache.GenreMissingMarkerSql.IS_MISSING + ") " +
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

    @Query("SELECT * FROM cached_tracks WHERE sourceId = :sourceId AND filepath = :filepath LIMIT 1")
    suspend fun getTrackBySourcePath(sourceId: String, filepath: String): CachedTrack?

    @Query("SELECT * FROM cached_tracks WHERE filepath LIKE '%' || :suffix")
    suspend fun getTracksByPathSuffix(suffix: String): List<CachedTrack>

    @Query("SELECT * FROM cached_tracks WHERE title = :title AND artist = :artist")
    suspend fun getTracksByTitleArtist(title: String, artist: String): List<CachedTrack>

    /**
     * MI-24: exact owner/path/previous-size AND genre compare-and-swap after physical
     * post-write verification. An altered file invalidates any old sync digest;
     * verifiedSha256 must be recomputed by the normal sync owner, not preserved.
     */
    @Query(
        "UPDATE cached_tracks SET genre = :newGenre, size = :newSize, " +
            "verifiedSize = 0, verifiedSha256 = '', verifiedAt = 0, verifiedAlgorithm = '' " +
            "WHERE sourceId = :sourceId AND id = :trackId AND filepath = :filepath " +
            "AND size = :oldSize AND genre = :oldGenre"
    )
    suspend fun updateGenreAfterVerifiedFileWrite(
        sourceId: String, trackId: String, filepath: String,
        oldSize: Long, oldGenre: String, newSize: Long, newGenre: String,
    ): Int

    /**
     * The indexed genre and its multi-value evidence are one Room transaction.
     * No update if a different owner, URI, size or existing genre raced the writer.
     * The caller is responsible for rereading and validating the physical file.
     */
    @Transaction
    suspend fun replaceGenreAfterVerifiedWrite(
        sourceId: String, trackId: String, filepath: String,
        oldSize: Long, oldGenre: String, newSize: Long,
        evidence: EmbeddedTagEvidence,
    ): Boolean {
        if (sourceId != evidence.sourceId || trackId != evidence.trackId ||
            newSize <= 0 || evidence.sizeBytes != newSize ||
            evidence.modifiedAt <= 0 ||
            evidence.parserVersion != EmbeddedTagEvidence.CURRENT_PARSER_VERSION ||
            evidence.genre.isBlank() || evidence.genreValues.isBlank()) return false
        val changed = updateGenreAfterVerifiedFileWrite(
            sourceId, trackId, filepath, oldSize, oldGenre, newSize, evidence.genre)
        if (changed != 1) return false
        putTagEvidence(listOf(evidence))
        return true
    }

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

    /**
     * ME-11A: exact cached scalar CAS AFTER an independently certified physical
     * postcheck. The producer must have a prior verified content hash/at and
     * enforce all owner, grant and journal gates outside Room. Clearing the
     * previous verified-copy hash is mandatory after any changed source bytes.
     */
    @Query(
        "UPDATE cached_tracks SET title=:newTitle,artist=:newArtist,album=:newAlbum," +
            "albumArtist=:newAlbumArtist,genre=:newGenre,trackNumber=:newTrackNumber," +
            "year=:newYear,size=:newSize,verifiedSize=0,verifiedSha256=''," +
            "verifiedAt=0,verifiedAlgorithm='' " +
            "WHERE sourceId=:sourceId AND id=:trackId AND filepath=:filepath " +
            "AND size=:previousSize AND verifiedSize=:previousSize " +
            "AND verifiedAt=:previousVerifiedAt AND verifiedSha256=:previousSha256 " +
            "AND title=:previousTitle AND artist=:previousArtist AND album=:previousAlbum " +
            "AND albumArtist=:previousAlbumArtist AND genre=:previousGenre " +
            "AND trackNumber=:previousTrackNumber AND year=:previousYear"
    )
    suspend fun casVerifiedMetadataScalars(
        sourceId: String,trackId: String,filepath: String,
        previousSize: Long,previousVerifiedAt: Long,previousSha256: String,
        previousTitle: String,previousArtist: String,previousAlbum: String,
        previousAlbumArtist: String,previousGenre: String,
        previousTrackNumber: Int,previousYear: Int,
        newSize: Long,newTitle: String,newArtist: String,newAlbum: String,
        newAlbumArtist: String,newGenre: String,newTrackNumber: Int,newYear: Int,
    ): Int

    @Query("DELETE FROM embedded_tag_evidence WHERE sourceId=:sourceId AND trackId=:trackId")
    suspend fun invalidateMetadataEvidenceForTrack(sourceId: String,trackId: String): Int

    @Query("DELETE FROM metadata_people_evidence WHERE sourceId=:sourceId AND trackId=:trackId")
    suspend fun invalidateMetadataPeopleEvidenceForTrack(sourceId: String,trackId: String): Int

    @Query(
        "SELECT * FROM metadata_applied_transaction " +
            "WHERE transactionId=:transactionId LIMIT 1",
    )
    suspend fun getAppliedMetadataTransaction(
        transactionId: String,
    ): MetadataAppliedTransaction?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAppliedMetadataTransaction(marker: MetadataAppliedTransaction)

    @Query(
        "UPDATE cached_tracks SET title=:newTitle,artist=:newArtist,album=:newAlbum," +
            "albumArtist=:newAlbumArtist,genre=:newGenre,trackNumber=:newTrackNumber," +
            "year=:newYear,size=:newSize,verifiedSize=0,verifiedSha256=''," +
            "verifiedAt=0,verifiedAlgorithm='',metadataRevision=:committedRevision " +
            "WHERE sourceId=:sourceId AND id=:trackId AND filepath=:filepath " +
            "AND metadataRevision=:previousRevision " +
            "AND size=:previousSize AND verifiedSize=:previousSize " +
            "AND verifiedAt=:previousVerifiedAt AND verifiedSha256=:previousSha256 " +
            "AND title=:previousTitle AND artist=:previousArtist AND album=:previousAlbum " +
            "AND albumArtist=:previousAlbumArtist AND genre=:previousGenre " +
            "AND trackNumber=:previousTrackNumber AND year=:previousYear",
    )
    suspend fun casVerifiedMetadataScalarsAtRevision(
        sourceId: String, trackId: String, filepath: String,
        previousRevision: Long, committedRevision: Long,
        previousSize: Long, previousVerifiedAt: Long,
        previousSha256: String, previousTitle: String, previousArtist: String,
        previousAlbum: String, previousAlbumArtist: String, previousGenre: String,
        previousTrackNumber: Int, previousYear: Int, newSize: Long,
        newTitle: String, newArtist: String, newAlbum: String,
        newAlbumArtist: String, newGenre: String, newTrackNumber: Int, newYear: Int,
    ): Int

    @Query(
        "UPDATE cached_tracks SET title=:newTitle,artist=:newArtist,album=:newAlbum," +
            "albumArtist=:newAlbumArtist,genre=:newGenre,trackNumber=:newTrackNumber," +
            "year=:newYear,size=:newSize,verifiedSize=0,verifiedSha256=''," +
            "verifiedAt=0,verifiedAlgorithm='',metadataRevision=:committedRevision " +
            "WHERE sourceId=:sourceId AND id=:trackId AND filepath=:filepath " +
            "AND metadataRevision=:previousRevision " +
            "AND size=:previousSize AND verifiedSize=0 AND verifiedAt=0 " +
            "AND verifiedSha256='' AND verifiedAlgorithm='' " +
            "AND title=:previousTitle AND artist=:previousArtist AND album=:previousAlbum " +
            "AND albumArtist=:previousAlbumArtist AND genre=:previousGenre " +
            "AND trackNumber=:previousTrackNumber AND year=:previousYear",
    )
    suspend fun casUnverifiedMetadataScalarsAtRevision(
        sourceId: String, trackId: String, filepath: String,
        previousRevision: Long, committedRevision: Long, previousSize: Long,
        previousTitle: String, previousArtist: String, previousAlbum: String,
        previousAlbumArtist: String, previousGenre: String,
        previousTrackNumber: Int, previousYear: Int, newSize: Long,
        newTitle: String, newArtist: String, newAlbum: String,
        newAlbumArtist: String, newGenre: String, newTrackNumber: Int, newYear: Int,
    ): Int

    @Query(
        "INSERT INTO metadata_revision_clock(sourceId,trackId,revision) " +
            "VALUES(:sourceId,:trackId,:revision) " +
            "ON CONFLICT(sourceId,trackId) DO UPDATE SET revision=" +
            "CASE WHEN revision < :revision THEN :revision ELSE revision END",
    )
    suspend fun retainMetadataRevision(sourceId: String, trackId: String, revision: Long)

    @Transaction
    suspend fun applyMetadataTransaction(
        request: MetadataTrackTransactionRequest,
    ): MetadataTrackTransactionResult {
        val existing = getAppliedMetadataTransaction(request.transactionId)
        if (existing != null) {
            val exact = existing.sourceId == request.sourceId &&
                existing.trackId == request.trackId &&
                existing.uri == request.filepath &&
                existing.originalRevision == request.previousRevision &&
                existing.candidateSha256 == request.candidateSha256 &&
                existing.candidateBytes == request.candidateBytes
            return if (exact) MetadataTrackTransactionResult.AlreadyApplied(existing)
            else MetadataTrackTransactionResult.Conflict
        }
        if (!request.transactionId.matches(
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
            ) ||
            request.sourceId.isBlank() || request.trackId.isBlank() ||
            !request.filepath.startsWith("content://") ||
            request.previousRevision < 0L || request.previousSize <= 0L ||
            request.newSize <= 0L || request.candidateBytes != request.newSize ||
            !request.candidateSha256.matches(Regex("[0-9a-f]{64}")) ||
            request.appliedAt <= 0L
        ) return MetadataTrackTransactionResult.Conflict

        val committedRevision = request.previousRevision + 1L
        val changed = if (request.previousVerifiedAt > 0L &&
            request.previousSha256.matches(Regex("[0-9a-f]{64}"))
        ) {
            casVerifiedMetadataScalarsAtRevision(
                request.sourceId, request.trackId, request.filepath,
                request.previousRevision, committedRevision, request.previousSize,
                request.previousVerifiedAt, request.previousSha256,
                request.previousTitle, request.previousArtist, request.previousAlbum,
                request.previousAlbumArtist, request.previousGenre,
                request.previousTrackNumber, request.previousYear,
                request.newSize, request.newTitle, request.newArtist, request.newAlbum,
                request.newAlbumArtist, request.newGenre,
                request.newTrackNumber, request.newYear,
            )
        } else if (request.previousVerifiedAt == 0L &&
            request.previousSha256.isEmpty()
        ) {
            casUnverifiedMetadataScalarsAtRevision(
                request.sourceId, request.trackId, request.filepath,
                request.previousRevision, committedRevision, request.previousSize,
                request.previousTitle, request.previousArtist, request.previousAlbum,
                request.previousAlbumArtist, request.previousGenre,
                request.previousTrackNumber, request.previousYear,
                request.newSize, request.newTitle, request.newArtist, request.newAlbum,
                request.newAlbumArtist, request.newGenre,
                request.newTrackNumber, request.newYear,
            )
        } else 0
        if (changed != 1) return MetadataTrackTransactionResult.Conflict

        retainMetadataRevision(request.sourceId, request.trackId, committedRevision)
        invalidateMetadataEvidenceForTrack(request.sourceId, request.trackId)
        invalidateMetadataPeopleEvidenceForTrack(request.sourceId, request.trackId)
        val committed = getTrackBySource(request.sourceId, request.trackId)
            ?: throw IllegalStateException("METADATA_ROW_DISAPPEARED")
        if (committed.metadataRevision != committedRevision) {
            throw IllegalStateException("METADATA_REVISION_NOT_ADVANCED")
        }
        val marker = MetadataAppliedTransaction(
            transactionId = request.transactionId,
            sourceId = request.sourceId,
            trackId = request.trackId,
            uri = request.filepath,
            originalRevision = request.previousRevision,
            committedRevision = committed.metadataRevision,
            candidateSha256 = request.candidateSha256,
            candidateBytes = request.candidateBytes,
            appliedAt = request.appliedAt,
        )
        insertAppliedMetadataTransaction(marker)
        return MetadataTrackTransactionResult.Applied(marker)
    }

    @Transaction
    suspend fun applyMetadataTransactionWithGenreEvidence(
        request: MetadataTrackTransactionRequest,
        evidence: EmbeddedTagEvidence,
    ): MetadataTrackTransactionResult {
        val result = applyMetadataTransaction(request)
        // Replaying an already-committed transaction must not republish the
        // evidence captured by that older revision. Marker and evidence were
        // written atomically when the transaction first applied.
        if (result is MetadataTrackTransactionResult.Applied)
            putTagEvidence(listOf(evidence))
        return result
    }

    /**
     * The row CAS and invalidation of stale tag evidence are atomic. We do NOT
     * invent a new parserVersion/modifiedAt or claim that unparsed tags vanished.
     * Cold reindex is needed to repopulate extended fields after this action.
     */
    @Transaction
    suspend fun commitMetadataAfterVerifiedWrite(
        sourceId: String,trackId: String,filepath: String,
        previousSize: Long,previousVerifiedAt: Long,previousSha256: String,
        previousTitle: String,previousArtist: String,previousAlbum: String,
        previousAlbumArtist: String,previousGenre: String,
        previousTrackNumber: Int,previousYear: Int,
        newSize: Long,newTitle: String,newArtist: String,newAlbum: String,
        newAlbumArtist: String,newGenre: String,newTrackNumber: Int,newYear: Int,
    ): Boolean {
        if (newSize <= 0L || previousSize <= 0L || previousVerifiedAt <= 0L ||
            !filepath.startsWith("content://") ||
            !previousSha256.matches(Regex("[0-9a-f]{64}"))) return false
        val changed=casVerifiedMetadataScalars(sourceId,trackId,filepath,
            previousSize,previousVerifiedAt,previousSha256,previousTitle,previousArtist,
            previousAlbum,previousAlbumArtist,previousGenre,previousTrackNumber,
            previousYear,newSize,newTitle,newArtist,newAlbum,newAlbumArtist,
            newGenre,newTrackNumber,newYear)
        if (changed != 1) return false
        invalidateMetadataEvidenceForTrack(sourceId,trackId)
        invalidateMetadataPeopleEvidenceForTrack(sourceId,trackId)
        return true
    }

    /**
     * ME-11E: cache-only CAS for rows which historically have no trustworthy
     * recorded SHA. This NEVER accepts a nonempty/partial prior proof and
     * requires the caller to have separately certified the original/candidate
     * source hashes using the protected journal. No changed scalar is guessed.
     */
    @Query(
        "UPDATE cached_tracks SET title=:newTitle,artist=:newArtist,album=:newAlbum," +
            "albumArtist=:newAlbumArtist,genre=:newGenre,trackNumber=:newTrackNumber," +
            "year=:newYear,size=:newSize,verifiedSize=0,verifiedSha256=''," +
            "verifiedAt=0,verifiedAlgorithm='' " +
            "WHERE sourceId=:sourceId AND id=:trackId AND filepath=:filepath " +
            "AND size=:previousSize AND verifiedSize=0 AND verifiedAt=0 " +
            "AND verifiedSha256='' AND verifiedAlgorithm='' " +
            "AND title=:previousTitle AND artist=:previousArtist AND album=:previousAlbum " +
            "AND albumArtist=:previousAlbumArtist AND genre=:previousGenre " +
            "AND trackNumber=:previousTrackNumber AND year=:previousYear"
    )
    suspend fun casUnverifiedMetadataScalars(
        sourceId: String,trackId: String,filepath: String,
        previousSize: Long,
        previousTitle: String,previousArtist: String,previousAlbum: String,
        previousAlbumArtist: String,previousGenre: String,
        previousTrackNumber: Int,previousYear: Int,
        newSize: Long,newTitle: String,newArtist: String,newAlbum: String,
        newAlbumArtist: String,newGenre: String,newTrackNumber: Int,newYear: Int,
    ): Int

    @Transaction
    suspend fun commitMetadataAfterVerifiedWriteUnhashed(
        sourceId: String,trackId: String,filepath: String,
        previousSize: Long,
        previousTitle: String,previousArtist: String,previousAlbum: String,
        previousAlbumArtist: String,previousGenre: String,
        previousTrackNumber: Int,previousYear: Int,
        newSize: Long,newTitle: String,newArtist: String,newAlbum: String,
        newAlbumArtist: String,newGenre: String,newTrackNumber: Int,newYear: Int,
    ): Boolean {
        if (newSize <= 0L || previousSize <= 0L ||
            !filepath.startsWith("content://")) return false
        val changed=casUnverifiedMetadataScalars(sourceId,trackId,filepath,
            previousSize,previousTitle,previousArtist,previousAlbum,
            previousAlbumArtist,previousGenre,previousTrackNumber,previousYear,
            newSize,newTitle,newArtist,newAlbum,newAlbumArtist,newGenre,
            newTrackNumber,newYear)
        if (changed != 1) return false
        invalidateMetadataEvidenceForTrack(sourceId,trackId)
        invalidateMetadataPeopleEvidenceForTrack(sourceId,trackId)
        return true
    }
    /**
     * ME-11I: one atomic Room transaction for the scalar first-value hint
     * AND the complete, verified multi-genre sidecar. Reuses the original
     * conditional CAS; NEVER replaces a cache row by ID alone.
     * The source file has already been independently verified by the caller;
     * this function cannot certify it or create a physical write permission.
     */
    @Transaction
    suspend fun commitMetadataAfterVerifiedWriteWithGenreEvidence(
        sourceId: String,trackId: String,filepath: String,
        previousSize: Long,previousVerifiedAt: Long,previousSha256: String,
        previousTitle: String,previousArtist: String,previousAlbum: String,
        previousAlbumArtist: String,previousGenre: String,
        previousTrackNumber: Int,previousYear: Int,
        newSize: Long,newTitle: String,newArtist: String,newAlbum: String,
        newAlbumArtist: String,newGenre: String,newTrackNumber: Int,newYear: Int,
        genreEvidence: EmbeddedTagEvidence,
    ): Boolean {
        val orderedGenres=genreEvidence.genreValues.split('\u0000')
        if (!org.michimusic.core.metadata.MetadataCommitCertification.mayMutateOriginal() ||
            genreEvidence.sourceId != sourceId || genreEvidence.trackId != trackId ||
            genreEvidence.sizeBytes != newSize || genreEvidence.modifiedAt <= 0L ||
            genreEvidence.parserVersion != EmbeddedTagEvidence.CURRENT_PARSER_VERSION ||
            genreEvidence.genre != newGenre || genreEvidence.genre.isBlank() ||
            orderedGenres.size !in 2..32 || orderedGenres.firstOrNull() != newGenre ||
            orderedGenres.any { it.isBlank() || it.length > 2048 } ||
            genreEvidence.title != newTitle || genreEvidence.artist != newArtist ||
            genreEvidence.album != newAlbum || genreEvidence.albumArtist != newAlbumArtist ||
            genreEvidence.trackNumber != newTrackNumber ||
            !filepath.startsWith("content://")) return false
        val updated=if (previousVerifiedAt > 0L) commitMetadataAfterVerifiedWrite(
            sourceId,trackId,filepath,previousSize,previousVerifiedAt,previousSha256,
            previousTitle,previousArtist,previousAlbum,previousAlbumArtist,previousGenre,
            previousTrackNumber,previousYear,newSize,newTitle,newArtist,newAlbum,
            newAlbumArtist,newGenre,newTrackNumber,newYear)
        else if (previousVerifiedAt == 0L && previousSha256.isEmpty())
            commitMetadataAfterVerifiedWriteUnhashed(
                sourceId,trackId,filepath,previousSize,previousTitle,previousArtist,
                previousAlbum,previousAlbumArtist,previousGenre,previousTrackNumber,
                previousYear,newSize,newTitle,newArtist,newAlbum,newAlbumArtist,
                newGenre,newTrackNumber,newYear)
        else false
        if (!updated) return false
        putTagEvidence(listOf(genreEvidence))
        return true
    }

    /**
     * ME-11Q: an exact owner/URI/version CAS for a provider-certified SAF rename.
     * No change to track IDs, titles, artist, genre or audio. Every old proof is
     * compared, and old embedded/people evidence is invalidated transactionally.
     * A NEW documentId may require a different track ID on some SAF libraries;
     * the caller MUST reject such providers unless stable IDs were certified.
     */
    @Query(
        "UPDATE cached_tracks SET filepath=:newUri,verifiedSize=0," +
            "verifiedSha256='',verifiedAt=0,verifiedAlgorithm='' " +
            "WHERE sourceId=:sourceId AND id=:trackId AND filepath=:oldUri " +
            "AND size=:previousSize AND verifiedSize=:oldVerifiedSize " +
            "AND verifiedSha256=:oldVerifiedSha256 AND verifiedAt=:oldVerifiedAt " +
            "AND verifiedAlgorithm=:oldVerifiedAlgorithm " +
            "AND title=:previousTitle AND artist=:previousArtist " +
            "AND album=:previousAlbum AND albumArtist=:previousAlbumArtist " +
            "AND genre=:previousGenre AND trackNumber=:previousTrackNumber AND year=:previousYear",
    )
    suspend fun casSafRenameIndexedUri(
        sourceId:String,trackId:String,oldUri:String,newUri:String,
        previousSize:Long,oldVerifiedSize:Long,oldVerifiedSha256:String,
        oldVerifiedAt:Long,oldVerifiedAlgorithm:String,
        previousTitle:String,previousArtist:String,previousAlbum:String,
        previousAlbumArtist:String,previousGenre:String,
        previousTrackNumber:Int,previousYear:Int,
    ): Int

    @Transaction
    suspend fun commitVerifiedSafRenameLocation(
        sourceId:String,trackId:String,oldUri:String,newUri:String,
        previousSize:Long,oldVerifiedSize:Long,oldVerifiedSha256:String,
        oldVerifiedAt:Long,oldVerifiedAlgorithm:String,
        previousTitle:String,previousArtist:String,previousAlbum:String,
        previousAlbumArtist:String,previousGenre:String,
        previousTrackNumber:Int,previousYear:Int,
    ): Boolean {
        // A stored verified rename journal is not sufficient to enable source
        // or index mutation without REAL external provider certification.
        if (!org.michimusic.data.metadata.MetadataSafRenameRoomPublicationCertification.URI_CAS_CERTIFIED ||
            trackId.isBlank() || previousSize <= 0 ||
            !oldUri.startsWith("content://") || !newUri.startsWith("content://")) return false
        val changed=casSafRenameIndexedUri(
            sourceId,trackId,oldUri,newUri,previousSize,oldVerifiedSize,
            oldVerifiedSha256,oldVerifiedAt,oldVerifiedAlgorithm,
            previousTitle,previousArtist,previousAlbum,previousAlbumArtist,
            previousGenre,previousTrackNumber,previousYear)
        if (changed != 1) return false
        invalidateMetadataEvidenceForTrack(sourceId,trackId)
        invalidateMetadataPeopleEvidenceForTrack(sourceId,trackId)
        return true
    }
}
