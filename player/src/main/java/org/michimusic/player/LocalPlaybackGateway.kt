package org.michimusic.player

import kotlinx.coroutines.flow.StateFlow
import org.michimusic.core.models.Track

interface LocalPlaybackGateway {
    val state: StateFlow<PlayerState>
    val isReady: StateFlow<Boolean>
    fun play()
    fun pause()
    fun seekTo(position: Long)
    fun skipNext()
    fun skipPrevious()
    fun skipToQueueIndex(index: Int)
    fun playQueue(tracks: List<Track>, startIndex: Int = 0)
    fun addToQueue(track: Track)
    fun removeFromQueue(index: Int)
    fun clearQueue()
    fun setRepeatMode(mode: Int)
    fun setShuffleMode(enabled: Boolean)
    fun cycleRepeatMode()
    fun toggleShuffle()
    fun setSleepTimer(durationMs: Long)
    fun cancelSleepTimer()
}
