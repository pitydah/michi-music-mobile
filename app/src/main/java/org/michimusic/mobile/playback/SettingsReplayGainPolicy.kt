package org.michimusic.mobile.playback

import org.michimusic.player.ReplayGainMode

/** Legacy Int ordinals and modern String enum names share one tolerant reader.
 * Never mutate the saved value on read. Wrong types/invalid ordinals fail closed.
 */
internal fun settingsReplayGainMode(raw: Any?): ReplayGainMode = when (raw) {
    is String -> ReplayGainMode.entries.firstOrNull { it.name == raw } ?: ReplayGainMode.OFF
    is Int -> ReplayGainMode.entries.getOrElse(raw) { ReplayGainMode.OFF }
    else -> ReplayGainMode.OFF
}
