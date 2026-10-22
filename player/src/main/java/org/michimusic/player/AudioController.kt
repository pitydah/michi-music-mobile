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
import kotlinx.coroutines.withContext
import org.michimusic.data.cache.HistoryEntity
import org.michimusic.core.models.Track

private const val TAG = "MichiAudio"

@OptIn(UnstableApi::class)
class AudioController(
    private val context: Context,
    private val scope: CoroutineScope,
) : LocalPlaybackGateway {
    private var mediaController: MediaController? = null
    private var connectStarted = false
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    private var positionJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var lastRecordedTrackId: String? = null
    private var lastRecordedAt: Long = 0L

    private var pendingQueueTracks: List<Track>? = null
    private var pendingStartIndex: Int = 0
    private var pendingAutoPlay: Boolean = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val ctrl = mediaController
            val status = if (isPlaying) {
                PlaybackStatus.Playing
            } else {
                when (ctrl?.playbackState) {
                    Player.STATE_BUFFERING -> PlaybackStatus.Buffering
                    Player.STATE_ENDED -> PlaybackStatus.Ended
                    Player.STATE_IDLE -> ctrl.playerError?.let { PlaybackErrorMapper.mapPlaybackError(it) } ?: PlaybackStatus.Idle
                    else -> PlaybackStatus.Paused
                }
            }
            _state.value = _state.value.copy(
                isPlaying = isPlaying,
                playbackStatus = status,
            )
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val ctrl = mediaController ?: return
            reconcileQueueFromController(ctrl)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val ctrl = mediaController ?: return
            reconcileQueueFromController(ctrl)
            val queue = _state.value.queue
            val index = ctrl.currentMediaItemIndex
            val track = if (index in queue.indices) queue[index] else {
                mediaItem?.mediaId?.let { LibraryProvider.extractSongId(it) }?.let { id ->
                    queue.find { it.id == id }
                }
            }
            if (track != null) {
                _state.value = _state.value.copy(
                    currentTrack = track,
                    queueIndex = if (index >= 0) index else queue.indexOf(track),
                    position = 0L,
                )
                recordPlayback(track)
            } else if (mediaItem == null && ctrl.mediaItemCount == 0) {
                _state.value = _state.value.copy(
                    currentTrack = null,
                    queueIndex = -1,
                    position = 0L,
                    isPlaying = false,
                    playbackStatus = PlaybackStatus.Idle,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val ctrl = mediaController ?: return
            val isPlaying = ctrl.isPlaying
            val status = when (playbackState) {
                Player.STATE_IDLE -> {
                    val err = ctrl.playerError?.let { PlaybackErrorMapper.mapPlaybackError(it) }
                    err ?: PlaybackStatus.Idle
                }
                Player.STATE_BUFFERING -> PlaybackStatus.Buffering
                Player.STATE_READY -> if (isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused
                Player.STATE_ENDED -> PlaybackStatus.Ended
                else -> PlaybackStatus.Idle
            }
            _state.value = _state.value.copy(
                playbackStatus = status,
                isPlaying = isPlaying,
                duration = if (playbackState == Player.STATE_READY) ctrl.duration.coerceAtLeast(0L) else _state.value.duration,
            )
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val mapped = PlaybackErrorMapper.mapPlaybackError(error)
            _state.value = _state.value.copy(
                isPlaying = false,
                playbackStatus = mapped,
                lastError = mapped,
            )
            stopPositionUpdates()
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
            try {
                val controller = future.get()
                mediaController = controller
                controller.addListener(listener)
                _isReady.value = true
                Log.d(TAG, "MediaController ready")
                executePendingPlayQueue()
                executePendingCommands()
            } catch (_: Exception) {
                _isReady.value = false
            }
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

    override fun play() {
        ensureConnected()
        Log.d(TAG, "play requested")
        mediaController?.play()
    }

    override fun pause() {
        ensureConnected()
        Log.d(TAG, "pause requested")
        mediaController?.pause()
    }

    override fun seekTo(position: Long) {
        ensureConnected()
        mediaController?.seekTo(position)
        _state.value = _state.value.copy(position = position)
    }

    override fun skipNext() {
        ensureConnected()
        Log.d(TAG, "skipNext requested")
        mediaController?.seekToNextMediaItem()
    }

    override fun skipPrevious() {
        ensureConnected()
        mediaController?.seekToPreviousMediaItem()
    }

    override fun skipToQueueIndex(index: Int) {
        ensureConnected()
        if (index in _state.value.queue.indices) {
            mediaController?.seekTo(index, 0L)
            _state.value = _state.value.copy(
                currentTrack = _state.value.queue[index],
                queueIndex = index,
                position = 0L,
            )
        }
    }

    override fun setRepeatMode(mode: Int) {
        ensureConnected()
        mediaController?.repeatMode = mode
    }

    override fun setShuffleMode(enabled: Boolean) {
        ensureConnected()
        mediaController?.shuffleModeEnabled = enabled
    }

    override fun cycleRepeatMode() {
        val current = _state.value.repeatMode
        val next = when (current) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        setRepeatMode(next)
    }

    override fun toggleShuffle() {
        ensureConnected()
        mediaController?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    override fun setSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        if (durationMs <= 0L) {
            _state.value = _state.value.copy(sleepTimerRemainingMs = 0L)
            sleepTimerJob = null
            return
        }

        val endsAt = System.currentTimeMillis() + durationMs
        _state.value = _state.value.copy(sleepTimerRemainingMs = durationMs)
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
                if (remaining <= 0L) {
                    pause()
                    sleepTimerJob = null
                    break
                }
                delay(1_000)
            }
        }
    }

    override fun cancelSleepTimer() {
        setSleepTimer(0L)
    }

    override fun playQueue(tracks: List<Track>, startIndex: Int) {
        val sleepTimerRemainingMs = _state.value.sleepTimerRemainingMs
        val startTrack = tracks.getOrNull(startIndex)
        Log.d(TAG, "playQueue requested")
        if (mediaController == null) {
            Log.d(TAG, "playQueue deferred: MediaController not ready")
            pendingQueueTracks = tracks
            pendingStartIndex = startIndex
            pendingAutoPlay = true
            _state.value = PlayerState(
                currentTrack = startTrack,
                queue = tracks,
                queueIndex = startIndex,
                isPlaying = false,
                playbackStatus = PlaybackStatus.Preparing,
                duration = startTrack?.duration ?: 0L,
                sleepTimerRemainingMs = sleepTimerRemainingMs,
            )
            ensureConnected()
            return
        }
        executePlayQueue(tracks, startIndex, autoPlay = true, sleepTimerRemainingMs, startTrack)
    }

    private fun executePendingPlayQueue() {
        pendingQueueTracks?.let { tracks ->
            Log.d(TAG, "Executing deferred playQueue")
            executePlayQueue(tracks, pendingStartIndex, pendingAutoPlay, 0L, tracks.getOrNull(pendingStartIndex))
            pendingQueueTracks = null
        }
    }

    private fun executePlayQueue(
        tracks: List<Track>,
        startIndex: Int,
        autoPlay: Boolean,
        sleepTimerRemainingMs: Long,
        startTrack: Track?,
    ) {
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(LibraryProvider.buildSongId(track.id))
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
            currentTrack = startTrack,
            queue = tracks,
            queueIndex = startIndex,
            isPlaying = false,
            playbackStatus = PlaybackStatus.Preparing,
            duration = startTrack?.duration ?: 0L,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
        )
        startTrack?.let(::recordPlayback)
        saveCurrentQueueToDb()
        if (autoPlay) mediaController?.play()
    }

    private fun reconcileQueueFromController(ctrl: MediaController) {
        val count = ctrl.mediaItemCount
        if (count == 0) {
            if (_state.value.queue.isNotEmpty()) {
                _state.value = _state.value.copy(queue = emptyList(), queueIndex = -1)
                saveCurrentQueueToDb()
            }
            return
        }
        val currentQueue = _state.value.queue
        val remainingQueueTracks = currentQueue.toMutableList()
        val reconciled = mutableListOf<Track>()
        var modified = false

        for (i in 0 until count) {
            val item = ctrl.getMediaItemAt(i)
            val trackId = LibraryProvider.extractSongId(item.mediaId) ?: item.mediaId
            val existingIndex = remainingQueueTracks.indexOfFirst { it.id == trackId }
            if (existingIndex >= 0) {
                reconciled.add(remainingQueueTracks.removeAt(existingIndex))
            } else {
                reconciled.add(
                    Track(
                        id = trackId,
                        title = item.mediaMetadata.title?.toString() ?: "",
                        artist = item.mediaMetadata.artist?.toString() ?: "",
                        album = item.mediaMetadata.albumTitle?.toString() ?: "",
                        filepath = item.localConfiguration?.uri?.toString() ?: "",
                        duration = 0L,
                    )
                )
                modified = true
            }
        }

        if (modified || reconciled != currentQueue) {
            val activeIndex = ctrl.currentMediaItemIndex
            val currentTrack = if (activeIndex in reconciled.indices) reconciled[activeIndex] else null
            _state.value = _state.value.copy(
                queue = reconciled,
                queueIndex = activeIndex,
                currentTrack = currentTrack ?: _state.value.currentTrack
            )
            saveCurrentQueueToDb()
        }
    }

    private sealed interface PendingQueueCommand {
        data class Add(val track: Track) : PendingQueueCommand
        data class PlayNext(val track: Track) : PendingQueueCommand
        data class Move(val trackId: String, val toIndex: Int) : PendingQueueCommand
        data class Remove(val trackId: String) : PendingQueueCommand
    }
    private val pendingCommands = mutableListOf<PendingQueueCommand>()

    private fun executePendingCommands() {
        val ctrl = mediaController ?: return
        if (pendingCommands.isEmpty()) return
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { cmd ->
            when (cmd) {
                is PendingQueueCommand.Add -> addToQueue(cmd.track)
                is PendingQueueCommand.PlayNext -> playNext(cmd.track)
                is PendingQueueCommand.Move -> {
                    val currentIndex = _state.value.queue.indexOfFirst { it.id == cmd.trackId }
                    if (currentIndex >= 0) {
                        moveQueueItem(currentIndex, cmd.toIndex)
                    }
                }
                is PendingQueueCommand.Remove -> {
                    val currentIndex = _state.value.queue.indexOfFirst { it.id == cmd.trackId }
                    if (currentIndex >= 0) {
                        removeFromQueue(currentIndex)
                    }
                }
            }
        }
    }

    override fun addToQueue(track: Track) {
        val ctrl = mediaController
        if (ctrl == null) {
            pendingCommands.add(PendingQueueCommand.Add(track))
            ensureConnected()
            return
        }
        val newQueue = _state.value.queue + track
        ctrl.addMediaItem(
            MediaItem.Builder()
                .setMediaId(LibraryProvider.buildSongId(track.id))
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
        saveCurrentQueueToDb()
    }

    override fun playNext(track: Track) {
        val ctrl = mediaController
        if (ctrl == null) {
            pendingCommands.add(PendingQueueCommand.PlayNext(track))
            ensureConnected()
            return
        }
        val currentQueue = _state.value.queue.toMutableList()
        val insertIndex = (ctrl.currentMediaItemIndex + 1).coerceIn(0, currentQueue.size)
        currentQueue.add(insertIndex, track)
        ctrl.addMediaItem(
            insertIndex,
            MediaItem.Builder()
                .setMediaId(LibraryProvider.buildSongId(track.id))
                .setUri(track.filepath)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build(),
        )
        _state.value = _state.value.copy(queue = currentQueue)
        saveCurrentQueueToDb()
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val ctrl = mediaController
        if (ctrl == null) {
            val trackId = _state.value.queue.getOrNull(fromIndex)?.id
            if (trackId != null) {
                pendingCommands.add(PendingQueueCommand.Move(trackId, toIndex))
            }
            ensureConnected()
            return
        }
        val currentQueue = _state.value.queue.toMutableList()
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices && fromIndex != toIndex) {
            val item = currentQueue.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            ctrl.moveMediaItem(fromIndex, toIndex)
            val activeIndex = ctrl.currentMediaItemIndex
            _state.value = _state.value.copy(queue = currentQueue, queueIndex = activeIndex)
            saveCurrentQueueToDb()
        }
    }

    override fun removeFromQueue(index: Int) {
        val ctrl = mediaController
        if (ctrl == null) {
            val trackId = _state.value.queue.getOrNull(index)?.id
            if (trackId != null) {
                pendingCommands.add(PendingQueueCommand.Remove(trackId))
            }
            ensureConnected()
            return
        }
        val newQueue = _state.value.queue.toMutableList()
        if (index in newQueue.indices) {
            newQueue.removeAt(index)
            ctrl.removeMediaItem(index)
            val activeIndex = ctrl.currentMediaItemIndex
            _state.value = _state.value.copy(queue = newQueue, queueIndex = activeIndex)
            saveCurrentQueueToDb()
        }
    }

    override fun clearQueue() {
        ensureConnected()
        Log.d(TAG, "clearQueue requested")
        pendingQueueTracks = null
        mediaController?.stop()
        mediaController?.clearMediaItems()
        cancelSleepTimer()
        _state.value = PlayerState()
    }

    fun restoreQueue(tracks: List<Track>, startIndex: Int, positionMs: Long, repeatMode: Int, shuffleMode: Boolean) {
        if (tracks.isEmpty()) return
        _state.value = _state.value.copy(
            queue = tracks,
            queueIndex = startIndex,
            position = positionMs,
            repeatMode = repeatMode,
            shuffleMode = shuffleMode,
        )
    }

    fun release() {
        stopPositionUpdates()
        cancelSleepTimer()
        mediaController?.release()
        mediaController = null
        connectStarted = false
        pendingQueueTracks = null
    }

    private fun saveCurrentQueueToDb() {
        val appDao = PlayerDependencies.appDao ?: return
        val s = _state.value
        scope.launch {
            withContext(Dispatchers.IO) {
                appDao.saveQueue(
                    org.michimusic.data.cache.QueueEntity(
                        trackIds = s.queue.joinToString(",") { it.id },
                        startIndex = s.queueIndex.coerceAtLeast(0),
                        positionMs = s.position,
                        repeatMode = s.repeatMode,
                        shuffleMode = s.shuffleMode,
                    )
                )
            }
        }
    }

    private fun recordPlayback(track: Track) {
        val appDao = PlayerDependencies.appDao ?: return
        val now = System.currentTimeMillis()
        if (lastRecordedTrackId == track.id && now - lastRecordedAt < 2_500L) return
        lastRecordedTrackId = track.id
        lastRecordedAt = now
        scope.launch {
            withContext(Dispatchers.IO) {
                val historyEnabled = appDao.getSetting("listening_history_enabled") != "false"
                if (!historyEnabled) return@withContext
                val playedAt = now
                appDao.insertHistory(HistoryEntity(trackId = track.id, playedAt = playedAt))
                appDao.incrementPlayCount(track.id, playedAt)
                appDao.trimHistory()
            }
        }
    }
}
