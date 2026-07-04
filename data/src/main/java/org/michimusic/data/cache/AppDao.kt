package org.michimusic.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int = 50): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: HistoryEntity)

    @Query("DELETE FROM play_history WHERE id NOT IN (SELECT id FROM play_history ORDER BY playedAt DESC LIMIT :limit)")
    suspend fun trimHistory(limit: Int = 250)

    @Query("SELECT * FROM play_counts WHERE trackId = :trackId")
    suspend fun getPlayCount(trackId: String): PlayCountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayCount(count: PlayCountEntity)

    @Query(
        """
        INSERT OR REPLACE INTO play_counts(trackId, playCount, lastPlayed)
        VALUES(
            :trackId,
            COALESCE((SELECT playCount FROM play_counts WHERE trackId = :trackId), 0) + 1,
            :playedAt
        )
        """
    )
    suspend fun incrementPlayCount(trackId: String, playedAt: Long)

    @Query("SELECT * FROM play_counts ORDER BY playCount DESC LIMIT :limit")
    suspend fun getTopTracks(limit: Int = 50): List<PlayCountEntity>

    @Query("SELECT * FROM saved_queue WHERE id = 0")
    suspend fun getSavedQueue(): QueueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQueue(queue: QueueEntity)

    @Query("DELETE FROM saved_queue")
    suspend fun clearQueue()

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun getSetting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: SettingsEntity)
}
