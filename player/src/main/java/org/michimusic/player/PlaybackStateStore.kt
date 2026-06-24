package org.michimusic.player

import android.content.Context
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

class PlaybackStateStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    data class SavedState(
        val mediaIds: List<String> = emptyList(),
        val startIndex: Int = 0,
        val positionMs: Long = 0L,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val shuffleMode: Boolean = false,
    )

    fun save(state: SavedState) {
        val json = JSONObject().apply {
            put("mediaIds", JSONArray(state.mediaIds))
            put("startIndex", state.startIndex)
            put("positionMs", state.positionMs)
            put("repeatMode", state.repeatMode)
            put("shuffleMode", state.shuffleMode)
        }
        prefs.edit().putString(KEY_STATE, json.toString()).apply()
    }

    fun restore(): SavedState {
        val raw = prefs.getString(KEY_STATE, null) ?: return SavedState()
        return try {
            val json = JSONObject(raw)
            val idsArr = json.optJSONArray("mediaIds")
            val ids = if (idsArr != null) {
                (0 until idsArr.length()).map { idsArr.getString(it) }
            } else emptyList()
            SavedState(
                mediaIds = ids,
                startIndex = json.optInt("startIndex", 0),
                positionMs = json.optLong("positionMs", 0L),
                repeatMode = json.optInt("repeatMode", Player.REPEAT_MODE_OFF),
                shuffleMode = json.optBoolean("shuffleMode", false),
            )
        } catch (_: Exception) {
            SavedState()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
    }

    companion object {
        private const val KEY_STATE = "saved_playback_state"
    }
}
