package org.michimusic.player

import android.content.Context
import android.content.SharedPreferences

object ReplayGainConfig {

    private const val PREFS_NAME = "michi_settings"
    private const val KEY_RG_MODE = "replaygain_mode"
    private const val KEY_RG_PREAMP_WITH = "replaygain_preamp_with"
    private const val KEY_RG_PREAMP_WITHOUT = "replaygain_preamp_without"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getMode(): ReplayGainMode = prefs?.let {
        val ordinal = it.getInt(KEY_RG_MODE, 0)
        ReplayGainMode.entries.getOrElse(ordinal) { ReplayGainMode.OFF }
    } ?: ReplayGainMode.OFF

    fun getPreAmp(): ReplayGainPreAmp = prefs?.let {
        ReplayGainPreAmp(
            with = it.getFloat(KEY_RG_PREAMP_WITH, 0f),
            without = it.getFloat(KEY_RG_PREAMP_WITHOUT, 0f),
        )
    } ?: ReplayGainPreAmp()

    fun isAutoSyncEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getBoolean("auto_sync", false)
    }
}
