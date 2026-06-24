package org.michimusic.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.Track
import java.io.File

class PlayerController(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val queue = _state.value.queue
                val index = player.currentMediaItemIndex
                if (index in queue.indices) {
                    _state.value = _state.value.copy(
                        currentTrack = queue[index],
                        queueIndex = index,
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.value = _state.value.copy(
                        duration = player.duration.coerceAtLeast(0L),
                    )
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.value = _state.value.copy(repeatMode = repeatMode)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _state.value = _state.value.copy(shuffleMode = shuffleModeEnabled)
            }
        })
    }

    private fun resolveUri(track: Track): Uri {
        val path = track.filepath
        return when {
            path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://") ->
                Uri.parse(path)
            path.startsWith("/") -> Uri.fromFile(File(path))
            else -> Uri.parse(path)
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(resolveUri(track))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build()
        }
        player.setMediaItems(mediaItems, startIndex, 0L)
        val state = PlayerState(
            currentTrack = tracks.getOrNull(startIndex),
            queue = tracks,
            queueIndex = startIndex,
            isPlaying = true,
            duration = tracks.getOrNull(startIndex)?.duration ?: 0L,
        )
        _state.value = state
        player.prepare()
        player.play()
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun skipNext() {
        player.seekToNextMediaItem()
    }

    fun skipPrevious() {
        player.seekToPreviousMediaItem()
    }

    fun seekTo(position: Long) {
        player.seekTo(position)
        _state.value = _state.value.copy(position = position)
    }

    fun addToQueue(track: Track) {
        val newQueue = _state.value.queue + track
        player.addMediaItem(
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(resolveUri(track))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build(),
        )
        _state.value = _state.value.copy(queue = newQueue)
    }

    fun removeFromQueue(index: Int) {
        val newQueue = _state.value.queue.toMutableList()
        if (index in newQueue.indices) {
            newQueue.removeAt(index)
            player.removeMediaItem(index)
            _state.value = _state.value.copy(queue = newQueue)
        }
    }

    fun clearQueue() {
        player.stop()
        player.clearMediaItems()
        _state.value = PlayerState()
    }

    fun setRepeatMode(mode: Int) {
        player.repeatMode = mode
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun getExoPlayer(): ExoPlayer = player

    fun release() {
        player.release()
    }
}
