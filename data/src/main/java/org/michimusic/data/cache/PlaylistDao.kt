package org.michimusic.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM cached_playlists ORDER BY name")
    suspend fun getAllPlaylists(): List<CachedPlaylist>

    @Query("SELECT * FROM cached_playlists ORDER BY name")
    fun observeAll(): Flow<List<CachedPlaylist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: CachedPlaylist)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<CachedPlaylist>)

    @Query("DELETE FROM cached_playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE cached_playlists SET name = :name WHERE id = :id")
    suspend fun renameById(id: String, name: String)

    @Query("SELECT * FROM cached_playlists WHERE id = :id")
    suspend fun getById(id: String): CachedPlaylist?

    @Query("UPDATE cached_playlists SET trackIds = :trackIds WHERE id = :id")
    suspend fun updateTrackIds(id: String, trackIds: String)

    @Query("UPDATE cached_playlists SET trackIds = :trackIds, trackCount = :trackCount WHERE id = :id")
    suspend fun updateTrackIdsAndCount(id: String, trackIds: String, trackCount: Int)

    @Query("DELETE FROM cached_playlists")
    suspend fun deleteAll()
}
