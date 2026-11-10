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

    @Query("UPDATE cached_tracks SET downloaded = 1 WHERE sourceId = :sourceId AND id = :id")
    suspend fun markDownloaded(sourceId: String, id: String)

    @Query("UPDATE cached_tracks SET downloaded = 1, filepath = :filepath WHERE sourceId = :sourceId AND id = :id")
    suspend fun markDownloadedWithPath(sourceId: String, id: String, filepath: String)
}
