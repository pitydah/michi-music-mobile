package org.michimusic.mobile.ui.components.premium

/**
 * State representation for standardized playback controls.
 */
data class PlaybackControlUiState(
    val isPlaying: Boolean = false,
    val canSkipPrevious: Boolean = true,
    val canSkipNext: Boolean = true,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: Int = 0, // 0: OFF, 1: ONE, 2: ALL
    val isLoading: Boolean = false,
)
