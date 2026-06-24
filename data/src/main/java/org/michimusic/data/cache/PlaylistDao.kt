package org.michimusic.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM cached_playlists ORDER BY name")
    suspend fun getAllPlaylists(): List<CachedPlaylist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<CachedPlaylist>)

    @Query("DELETE FROM cached_playlists")
    suspend fun deleteAll()
}
