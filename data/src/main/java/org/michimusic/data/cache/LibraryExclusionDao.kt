package org.michimusic.data.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Never a physical media file: merely a user-visible catalog exclusion. */
@Entity(tableName = "library_exclusions", primaryKeys = ["sourceId", "trackId"])
data class LibraryExclusionEntity(
    val sourceId: String,
    val trackId: String,
    val guardFingerprint: String,
    val title: String,
    val artist: String,
    val album: String,
    val reason: String = "USER_HIDDEN",
    val createdAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = 1,
)

@Dao
abstract class LibraryExclusionDao {
    @Query("SELECT * FROM library_exclusions WHERE sourceId = :sourceId ORDER BY createdAt DESC, trackId")
    abstract suspend fun forSource(sourceId: String): List<LibraryExclusionEntity>

    @Query("SELECT * FROM library_exclusions WHERE sourceId = :sourceId ORDER BY createdAt DESC, trackId")
    abstract fun observeForSource(sourceId: String): Flow<List<LibraryExclusionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun hide(entry: LibraryExclusionEntity): Long

    /** Room executes collection inserts transactionally. ABORT is all-or-nothing. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun hideBatch(entries: List<LibraryExclusionEntity>): List<Long>

    /**
     * Copy exclusion metadata when an old SAF root source id is migrated.
     *
     * Room cannot verify `INSERT ... SELECT` as a cursor-returning @Query on every
     * compiler version. This is a real transaction over the DAO's own insert path,
     * so duplicates are ignored one row at a time and the count is truthful.
     */
    @Transaction
    open suspend fun copyLegacySource(oldSource: String, newSource: String): Int {
        var copied = 0
        for (entry in forSource(oldSource)) {
            if (hide(entry.copy(sourceId = newSource)) != -1L) copied++
        }
        return copied
    }

    /** Deletes only an exclusion row. No audio file or downloaded copy is touched. */
    @Query("DELETE FROM library_exclusions WHERE sourceId = :sourceId AND trackId = :trackId")
    abstract suspend fun restore(sourceId: String, trackId: String): Int
}
