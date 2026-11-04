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

    // K16-02: playlist IDs are only unique inside their library, so every
    // mutation by ID must name the owner as well.
    @Query("DELETE FROM cached_playlists WHERE sourceId = :sourceId AND id = :id")
    suspend fun deleteById(id: String, sourceId: String)

    @Query("UPDATE cached_playlists SET name = :name WHERE sourceId = :sourceId AND id = :id")
    suspend fun renameById(id: String, sourceId: String, name: String)

    @Query("SELECT * FROM cached_playlists WHERE sourceId = :sourceId AND id = :id")
    suspend fun getById(id: String, sourceId: String): CachedPlaylist?

    @Query("UPDATE cached_playlists SET trackIds = :trackIds WHERE sourceId = :sourceId AND id = :id")
    suspend fun updateTrackIds(id: String, sourceId: String, trackIds: String)

    @Query("UPDATE cached_playlists SET trackIds = :trackIds, trackCount = :trackCount WHERE sourceId = :sourceId AND id = :id")
    suspend fun updateTrackIdsAndCount(id: String, sourceId: String, trackIds: String, trackCount: Int)

    @Query("DELETE FROM cached_playlists")
    suspend fun deleteAll()
}
