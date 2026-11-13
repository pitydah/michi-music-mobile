package org.michimusic.data.repository

import android.content.ContentResolver
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_OEM_GENRE_QUERIES = 128
private const val OEM_RECOVERY_BUDGET_NANOS = 2_000_000_000L

/** Monotonic time guard. Blocking OEM resolver calls require separate device QA. */
internal fun genreRecoveryBudgetExceeded(startNanos: Long, nowNanos: Long, budgetNanos: Long): Boolean =
    nowNanos - startNanos >= budgetNanos

/**
 * Exceptional, bounded recovery when an OEM MediaStore rejects/omits GENRE.
 * Uses the platform genre/members indexes ONCE within the already authorized
 * IO library read. No content file opens, no additional scan or background job.
 * A provider exception, null cursor, or safety limit returns no recovery data.
 */
internal suspend fun recoverMediaStoreGenreMemberships(
    resolver: ContentResolver,
    volume: String,
    permittedAudioIds: Set<Long>,
    timeBudgetNanos: Long = OEM_RECOVERY_BUDGET_NANOS,
    clockNanos: () -> Long = System::nanoTime,
): Map<Long, String> {
    if (permittedAudioIds.isEmpty() || timeBudgetNanos <= 0) return emptyMap()
    val startNanos = clockNanos()
    fun outOfTime() = genreRecoveryBudgetExceeded(startNanos, clockNanos(), timeBudgetNanos)
    try {
        val genres = mutableListOf<Pair<Long, String>>()
        if (outOfTime()) return emptyMap()
        val genreCursor = resolver.query(
            MediaStore.Audio.Genres.getContentUri(volume),
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, null,
        ) ?: return emptyMap()
        genreCursor.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (outOfTime()) return emptyMap()
                if (genres.size >= MAX_OEM_GENRE_QUERIES) return emptyMap()
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn).orEmpty().trim()
                if (id > 0 && name.isNotEmpty()) genres += id to name
            }
        }
        val index = MediaStoreGenreRecoveryIndex(permittedAudioIds)
        for ((genreId, name) in genres) {
            currentCoroutineContext().ensureActive()
            if (outOfTime()) return emptyMap()
            val memberCursor = resolver.query(
                MediaStore.Audio.Genres.Members.getContentUri(volume, genreId),
                arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                null, null, null,
            ) ?: return emptyMap()
            memberCursor.use { cursor ->
                val audioIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.AUDIO_ID)
                var memberRows = 0
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    // Sample clock only every 64 rows to bound overhead on large libraries.
                    if ((memberRows and 63) == 0 && outOfTime()) return emptyMap()
                    memberRows += 1
                    if (!index.record(cursor.getLong(audioIdColumn), name)) return emptyMap()
                }
            }
        }
        if (outOfTime()) return emptyMap()
        return index.result()
    } catch (cancel: CancellationException) {
        throw cancel // Never turn user cancellation into a stale partial catalog.
    } catch (_: Exception) {
        return emptyMap() // Unreliable OEM metadata must not hide the library.
    }
}
