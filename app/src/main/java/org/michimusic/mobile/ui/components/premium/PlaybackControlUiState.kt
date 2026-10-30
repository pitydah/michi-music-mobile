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
) {
    fun shuffleContentDescription(): String =
        if (isShuffleEnabled) "Desactivar modo aleatorio" else "Activar modo aleatorio"

    fun repeatContentDescription(): String = when (repeatMode) {
        1 -> "Repetición: una pista"
        2 -> "Repetición: todo"
        else -> "Activar repetición"
    }

    fun playPauseContentDescription(): String = when {
        isLoading -> "Cargando audio"
        isPlaying -> "Pausar"
        else -> "Reproducir"
    }
}
