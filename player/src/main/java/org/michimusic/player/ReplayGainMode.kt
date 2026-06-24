package org.michimusic.player

enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,
    DYNAMIC,
}

data class ReplayGainPreAmp(
    val with: Float = 0f,
    val without: Float = 0f,
)
