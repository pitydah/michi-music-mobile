package org.michimusic.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReplayGainDao {
    @Query("SELECT * FROM replaygain_cache WHERE trackId = :trackId")
    suspend fun getReplayGain(trackId: String): ReplayGainEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReplayGainEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ReplayGainEntity>)
}
