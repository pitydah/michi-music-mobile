package org.michimusic.mobile.ui.screens

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Evidence of an accepted playlist queue command, NOT verified audible playback. */
internal data class PlaylistLaunchEvidence(
    val playlistId: String,
    val acceptedAtEpochMs: Long,
)

/**
 * Private local continuity. Read/write only on IO; no preference access in
 * Compose construction. Never stores track URIs, media files or listening events.
 */
internal class PlaylistLaunchHistoryStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private fun prefs() = appContext.getSharedPreferences("michi_playlist_launch_history", Context.MODE_PRIVATE)

    suspend fun read(): PlaylistLaunchEvidence? = withContext(io) {
        runCatching {
            val id = prefs().getString("playlist_id", "")?.trim().orEmpty()
            val at = prefs().getLong("accepted_at", 0L)
            val now = System.currentTimeMillis()
            if (id.isBlank() || at <= 0 || at > now || now - at > MAX_AGE_MS) null
            else PlaylistLaunchEvidence(id, at)
        }.getOrNull()
    }

    suspend fun recordAccepted(playlistId: String, trackCount: Int): Boolean = withContext(io) {
        val id = playlistId.trim()
        if (id.isBlank() || trackCount <= 0) return@withContext false
        runCatching {
            prefs().edit().putString("playlist_id", id)
                .putLong("accepted_at", System.currentTimeMillis()).commit()
        }.getOrDefault(false)
    }

    suspend fun clear() = withContext(io) {
        runCatching { prefs().edit().clear().commit() }
        Unit
    }

    companion object { private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 }
}

/**
 * User-disabled listening history must suppress playlist continuity. Stale or
 * deleted IDs must not be substituted with another playlist or a synthetic name.
 */
internal fun validPlaylistContinuityId(
    evidence: PlaylistLaunchEvidence?,
    historyEnabled: Boolean,
    currentPlaylistTrackCounts: Map<String, Int>,
): String? = evidence?.playlistId?.takeIf {
    historyEnabled && (currentPlaylistTrackCounts[it] ?: 0) > 0
}
