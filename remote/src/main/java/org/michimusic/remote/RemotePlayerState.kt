package org.michimusic.remote

data class RemotePlayerState(
    val state: String = "idle",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L,
    val position: Long = 0L,
    val volume: Int = 70,
    val sourceType: String = "",
    val sourceLabel: String = "",
    val destination: String = "local",
    val coverUrl: String = "",
) {
    val isPlaying: Boolean get() = state == "playing"
    val isPaused: Boolean get() = state == "paused"
    val isIdle: Boolean get() = state == "idle"
}
