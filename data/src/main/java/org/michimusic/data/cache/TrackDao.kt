package org.michimusic.data.cache

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM cached_tracks ORDER BY title")
    fun getAllTracks(): Flow<List<CachedTrack>>

    @Query("SELECT * FROM cached_tracks ORDER BY title")
    fun getAllTracksPagingSource(): PagingSource<Int, CachedTrack>

    @Query("SELECT * FROM cached_tracks WHERE id = :id")
    suspend fun getTrackById(id: String): CachedTrack?

    @Query("SELECT * FROM cached_tracks WHERE downloaded = 1")
    fun getDownloadedTracks(): Flow<List<CachedTrack>>

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

    @Query("UPDATE cached_tracks SET downloaded = 1 WHERE id = :id")
    suspend fun markDownloaded(id: String)

    @Query("UPDATE cached_tracks SET downloaded = 1, filepath = :filepath WHERE id = :id")
    suspend fun markDownloadedWithPath(id: String, filepath: String)
}
