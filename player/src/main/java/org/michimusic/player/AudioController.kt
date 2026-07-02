package org.michimusic.player

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.michimusic.core.models.Track

@OptIn(UnstableApi::class)
class AudioController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var mediaController: MediaController? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var positionJob: Job? = null
    @Volatile
    private var connectStarted = false
    private var connectJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val ctrl = mediaController ?: return
            val queue = _state.value.queue
            val index = ctrl.currentMediaItemIndex
            if (index in queue.indices) {
                _state.value = _state.value.copy(
                    currentTrack = queue[index],
                    queueIndex = index,
                    position = 0L,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val ctrl = mediaController ?: return
            if (playbackState == Player.STATE_READY) {
                _state.value = _state.value.copy(
                    duration = ctrl.duration.coerceAtLeast(0L),
                )
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.value = _state.value.copy(repeatMode = repeatMode)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.value = _state.value.copy(shuffleMode = shuffleModeEnabled)
        }
    }

    fun ensureConnected() {
        if (connectStarted) return
        connectStarted = true
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MichiPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            val controller = future.get()
            mediaController = controller
            controller.addListener(listener)
        }, MoreExecutors.directExecutor())
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionJob = scope.launch {
            while (isActive) {
                mediaController?.let { ctrl ->
                    _state.value = _state.value.copy(position = ctrl.currentPosition)
                }
                delay(250)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun play() {
        ensureConnected()
        mediaController?.play()
    }

    fun pause() {
        ensureConnected()
        mediaController?.pause()
    }

    fun seekTo(position: Long) {
        ensureConnected()
        mediaController?.seekTo(position)
        _state.value = _state.value.copy(position = position)
    }

    fun skipNext() {
        ensureConnected()
        mediaController?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        ensureConnected()
        mediaController?.seekToPreviousMediaItem()
    }

    fun setRepeatMode(mode: Int) {
        ensureConnected()
        mediaController?.repeatMode = mode
    }

    fun toggleShuffle() {
        ensureConnected()
        mediaController?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        ensureConnected()
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.filepath)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build()
        }
        mediaController?.setMediaItems(mediaItems, startIndex, 0L)
        _state.value = PlayerState(
            currentTrack = tracks.getOrNull(startIndex),
            queue = tracks,
            queueIndex = startIndex,
            isPlaying = true,
            duration = tracks.getOrNull(startIndex)?.duration ?: 0L,
        )
        mediaController?.play()
    }

    fun addToQueue(track: Track) {
        ensureConnected()
        val newQueue = _state.value.queue + track
        mediaController?.addMediaItem(
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.filepath)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build(),
        )
        _state.value = _state.value.copy(queue = newQueue)
    }

    fun removeFromQueue(index: Int) {
        ensureConnected()
        val newQueue = _state.value.queue.toMutableList()
        if (index in newQueue.indices) {
            newQueue.removeAt(index)
            mediaController?.removeMediaItem(index)
            _state.value = _state.value.copy(queue = newQueue)
        }
    }

    fun clearQueue() {
        ensureConnected()
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _state.value = PlayerState()
    }

    fun release() {
        stopPositionUpdates()
        mediaController?.release()
        mediaController = null
    }
}
