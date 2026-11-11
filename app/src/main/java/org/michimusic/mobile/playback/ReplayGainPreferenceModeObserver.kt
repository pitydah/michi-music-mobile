package org.michimusic.mobile.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.michimusic.player.ReplayGainMode

/** Read-only listener over the same michi_settings keys used by Settings and
 * the audio service. Collect only while Signal Truth is visible; no polling.
 */
internal class ReplayGainPreferenceModeObserver(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "michi_settings", Context.MODE_PRIVATE)

    fun observe(): Flow<ReplayGainMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "replaygain_mode") trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map {
        settingsReplayGainMode(preferences.all["replaygain_mode"])
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
