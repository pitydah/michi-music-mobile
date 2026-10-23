package org.michimusic.player

import org.michimusic.core.models.Track

data class PlayerState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueEntries: List<org.michimusic.core.models.QueueEntry> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playbackStatus: PlaybackStatus = PlaybackStatus.Idle,
    val position: Long = 0L,
    val duration: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleMode: Boolean = false,
    val sleepTimerRemainingMs: Long = 0L,
    val lastError: PlaybackStatus.Error? = null,
)
