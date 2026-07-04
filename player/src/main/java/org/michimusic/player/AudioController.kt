package org.michimusic.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
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

private const val TAG = "MichiAudio"

@OptIn(UnstableApi::class)
class AudioController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var mediaController: MediaController? = null
    private var connectStarted = false
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var positionJob: Job? = null

    private var pendingQueueTracks: List<Track>? = null
    private var pendingStartIndex: Int = 0
    private var pendingAutoPlay: Boolean = false

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

    init {
        Log.d(TAG, "AudioController created")
    }

    fun ensureConnected() {
        if (mediaController != null) return
        if (connectStarted) {
            Log.d(TAG, "ensureConnected ignored: already started")
            return
        }
        Log.d(TAG, "ensureConnected called")
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
            Log.d(TAG, "MediaController ready")
            executePendingPlayQueue()
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
        Log.d(TAG, "play requested")
        mediaController?.play()
    }

    fun pause() {
        ensureConnected()
        Log.d(TAG, "pause requested")
        mediaController?.pause()
    }

    fun seekTo(position: Long) {
        ensureConnected()
        mediaController?.seekTo(position)
        _state.value = _state.value.copy(position = position)
    }

    fun skipNext() {
        ensureConnected()
        Log.d(TAG, "skipNext requested")
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
        Log.d(TAG, "playQueue requested")
        if (mediaController == null) {
            Log.d(TAG, "playQueue deferred: MediaController not ready")
            pendingQueueTracks = tracks
            pendingStartIndex = startIndex
            pendingAutoPlay = true
            _state.value = PlayerState(
                currentTrack = tracks.getOrNull(startIndex),
                queue = tracks,
                queueIndex = startIndex,
                isPlaying = true,
                duration = tracks.getOrNull(startIndex)?.duration ?: 0L,
            )
            ensureConnected()
            return
        }
        executePlayQueue(tracks, startIndex, autoPlay = true)
    }

    private fun executePendingPlayQueue() {
        pendingQueueTracks?.let { tracks ->
            Log.d(TAG, "Executing deferred playQueue")
            executePlayQueue(tracks, pendingStartIndex, pendingAutoPlay)
            pendingQueueTracks = null
        }
    }

    private fun executePlayQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean) {
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
            isPlaying = autoPlay,
            duration = tracks.getOrNull(startIndex)?.duration ?: 0L,
        )
        if (autoPlay) mediaController?.play()
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
        Log.d(TAG, "clearQueue requested")
        pendingQueueTracks = null
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _state.value = PlayerState()
    }

    fun release() {
        stopPositionUpdates()
        mediaController?.release()
        mediaController = null
        connectStarted = false
        pendingQueueTracks = null
    }
}
