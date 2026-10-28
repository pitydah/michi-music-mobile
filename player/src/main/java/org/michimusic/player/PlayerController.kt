package org.michimusic.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.media.AudioDeviceInfo
import org.michimusic.core.models.Track
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.data.cache.ReplayGainEntity
import org.michimusic.data.repository.LocalMediaRepository
import java.io.File

private const val TAG = "PlayerController"

@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    audioProcessors: List<AudioProcessor> = emptyList(),
    replayGainDao: ReplayGainDao? = null,
    private val audioEffects: MichiAudioEffects? = null,
    private val usbDacManager: UsbDacManager? = null,
    private val signalTruthTracker: SignalTruthTracker? = null,
) {

    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)

    private val repository = replayGainDao?.let { LocalMediaRepository(context, it) }
        ?: LocalMediaRepository(context, createNoopReplayGainDao())

    private val routeObserver = usbDacManager?.getAudioRouteObserver()

    internal data class AudioTrackRecord(
        val track: android.media.AudioTrack,
        val generation: Long,
        val configKey: String,
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: Int,
        var isInitialized: Boolean = false,
        var isReleased: Boolean = false,
    )

    private var activeTrackGeneration: Long = 0L
    private val pendingGenerationsLock = Any()
    private val trackRecords = mutableListOf<AudioTrackRecord>()

    private fun configKey(config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig): String {
        return "${config.encoding}_${config.sampleRate}_${config.channelConfig}_${config.tunneling}_${config.offload}_${config.bufferSize}"
    }

    internal fun registerCreatedAudioTrack(track: android.media.AudioTrack, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig): Long {
        val record = synchronized(pendingGenerationsLock) {
            val next = ++activeTrackGeneration
            val rec = AudioTrackRecord(
                track = track,
                generation = next,
                configKey = configKey(config),
                sampleRate = config.sampleRate,
                channelCount = java.lang.Integer.bitCount(config.channelConfig),
                encoding = config.encoding,
            )
            trackRecords.add(rec)
            rec
        }
        routeObserver?.attachAudioTrack(track, record.generation)
        return record.generation
    }

    private val audioTrackProvider = DefaultAudioSink.AudioTrackProvider { config, attr, sessionId ->
        val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attr, sessionId)
        registerCreatedAudioTrack(track, config)
        track
    }

    private val audioSink = try {
        val builder = DefaultAudioSink.Builder(context)
            .setAudioTrackProvider(audioTrackProvider)
        if (audioProcessors.isNotEmpty()) {
            builder.setAudioProcessors(audioProcessors.toTypedArray()).build()
        } else {
            builder.build()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Custom AudioSink unavailable; PCM instrumentation will be degraded", e)
        signalTruthTracker?.markInstrumentationDegraded()
        DefaultAudioSink.Builder(context)
            .setAudioTrackProvider(audioTrackProvider)
            .build()
    }

    internal val audioRendererEventListener = object : androidx.media3.exoplayer.audio.AudioRendererEventListener {
        override fun onAudioInputFormatChanged(
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            signalTruthTracker?.onDecoderInputFormat(format)
        }

        override fun onAudioTrackInitialized(audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
            val key = configKey(audioTrackConfig)
            val record = synchronized(pendingGenerationsLock) {
                val rec = trackRecords.firstOrNull { it.configKey == key && !it.isInitialized && !it.isReleased }
                    ?: trackRecords.lastOrNull { !it.isInitialized && !it.isReleased }
                rec?.isInitialized = true
                rec
            }
            val gen = record?.generation ?: activeTrackGeneration
            signalTruthTracker?.onAudioTrackInitialized(
                sampleRate = audioTrackConfig.sampleRate,
                channelCount = java.lang.Integer.bitCount(audioTrackConfig.channelConfig),
                encoding = audioTrackConfig.encoding,
                generation = gen,
            )
        }

        override fun onAudioTrackReleased(audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
            val key = configKey(audioTrackConfig)
            val record = synchronized(pendingGenerationsLock) {
                // Find uninitialized instance first (actual release in platform AudioTrack changes state to STATE_UNINITIALIZED)
                val byState = trackRecords.firstOrNull { rec ->
                    rec.configKey == key && !rec.isReleased &&
                        runCatching { rec.track.state == android.media.AudioTrack.STATE_UNINITIALIZED }.getOrDefault(false)
                }
                val matched = byState
                    ?: trackRecords.firstOrNull { rec -> rec.configKey == key && !rec.isReleased }
                    ?: trackRecords.firstOrNull { !it.isReleased }

                matched?.isReleased = true
                if (trackRecords.size > 20) {
                    trackRecords.removeAll { it.isReleased }
                }
                matched
            }
            val gen = record?.generation ?: activeTrackGeneration
            val track = record?.track
            signalTruthTracker?.onAudioTrackReleased(gen)
            routeObserver?.detachAudioTrack(audioTrack = track, generation = gen)
        }
    }

    private val renderersFactory = RenderersFactory { handler, videoListener, audioListener, textOutput, metadataOutput ->
        try {
            arrayOf(
                MediaCodecAudioRenderer(
                    context,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioRendererEventListener,
                    audioSink,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Custom audio renderer unavailable; using the standard Media3 factory", e)
            signalTruthTracker?.markInstrumentationDegraded()
            DefaultRenderersFactory(context).createRenderers(
                handler, videoListener, audioListener, textOutput, metadataOutput,
            ).filter { it.trackType == C.TRACK_TYPE_AUDIO }.toTypedArray().also {
                check(it.isNotEmpty()) { "Media3 fallback did not supply an audio renderer" }
            }
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var positionJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val status = if (isPlaying) {
                    PlaybackStatus.Playing
                } else {
                    when (player.playbackState) {
                        Player.STATE_BUFFERING -> PlaybackStatus.Buffering
                        Player.STATE_ENDED -> PlaybackStatus.Ended
                        Player.STATE_IDLE -> player.playerError?.let { PlaybackErrorMapper.mapPlaybackError(it) } ?: PlaybackStatus.Idle
                        else -> PlaybackStatus.Paused
                    }
                }
                _state.value = _state.value.copy(
                    isPlaying = isPlaying,
                    playbackStatus = status,
                )
                routeObserver?.onPlaybackStateChanged(isPlaying)
                signalTruthTracker?.setPlaybackActive(isPlaying)
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val count = player.mediaItemCount
                if (count == 0) {
                    if (_state.value.queue.isNotEmpty()) {
                        _state.value = _state.value.copy(queue = emptyList(), queueEntries = emptyList(), queueIndex = -1)
                    }
                    return
                }
                val currentQueue = _state.value.queue
                val remainingQueueTracks = currentQueue.toMutableList()
                val reconciled = mutableListOf<Track>()
                for (i in 0 until count) {
                    val item = player.getMediaItemAt(i)
                    val existingIndex = remainingQueueTracks.indexOfFirst { it.id == item.mediaId }
                    if (existingIndex >= 0) {
                        reconciled.add(remainingQueueTracks.removeAt(existingIndex))
                    } else {
                        reconciled.add(
                            Track(
                                id = item.mediaId,
                                title = item.mediaMetadata.title?.toString() ?: "",
                                artist = item.mediaMetadata.artist?.toString() ?: "",
                                album = item.mediaMetadata.albumTitle?.toString() ?: "",
                                filepath = item.localConfiguration?.uri?.toString() ?: "",
                                duration = 0L,
                            )
                        )
                    }
                }
                if (reconciled != currentQueue) {
                    val activeIndex = player.currentMediaItemIndex
                    val existingEntries = _state.value.queueEntries.toMutableList()
                    val reconciledEntries = reconciled.mapIndexed { idx, trk ->
                        val matchIdx = existingEntries.indexOfFirst { it.canonicalTrackId == trk.id }
                        if (matchIdx >= 0) {
                            existingEntries.removeAt(matchIdx).copy(position = idx)
                        } else {
                            org.michimusic.core.models.QueueEntry(canonicalTrackId = trk.id, track = trk, position = idx)
                        }
                    }
                    _state.value = _state.value.copy(
                        queue = reconciled,
                        queueEntries = reconciledEntries,
                        queueIndex = activeIndex,
                        currentTrack = reconciled.getOrNull(activeIndex) ?: _state.value.currentTrack,
                    )
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val count = player.mediaItemCount
                val queue = _state.value.queue
                val index = player.currentMediaItemIndex
                if (index in queue.indices) {
                    val track = queue[index]
                    _state.value = _state.value.copy(
                        currentTrack = track,
                        queueIndex = index,
                        position = 0L,
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // A new AudioTrack may have been installed during a seamless item
                    // transition without another onIsPlayingChanged(true) event.
                    routeObserver?.onPlaybackStateChanged(player.isPlaying)
                    _state.value = _state.value.copy(
                        duration = player.duration.coerceAtLeast(0L),
                        playbackStatus = if (_state.value.isPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused,
                    )
                    if (player.audioSessionId != 0) {
                        audioEffects?.bindAudioSession(player.audioSessionId)
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    _state.value = _state.value.copy(playbackStatus = PlaybackStatus.Buffering)
                } else if (playbackState == Player.STATE_ENDED) {
                    signalTruthTracker?.onPlaybackStopped()
                    _state.value = _state.value.copy(playbackStatus = PlaybackStatus.Ended, isPlaying = false)
                } else if (playbackState == Player.STATE_IDLE) {
                    signalTruthTracker?.onPlaybackStopped()
                    val err = player.playerError?.let { PlaybackErrorMapper.mapPlaybackError(it) }
                    _state.value = _state.value.copy(
                        playbackStatus = err ?: PlaybackStatus.Idle,
                        isPlaying = false,
                    )
                }
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

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != 0) {
                    audioEffects?.bindAudioSession(audioSessionId)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.value = _state.value.copy(repeatMode = repeatMode)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _state.value = _state.value.copy(shuffleMode = shuffleModeEnabled)
            }
        })

        if (player.audioSessionId != 0) {
            audioEffects?.bindAudioSession(player.audioSessionId)
        }

        if (usbDacManager != null) {
            usbDacManager.onUsbDisconnectedListener = {
                // Buffering with playWhenReady=true must also be paused before Android
                // switches the pending audio to the built-in speaker.
                if (player.playWhenReady) {
                    Log.i(TAG, "USB desconectado; pausando reproducción activa/pendiente")
                    pause()
                }
            }
            controllerScope.launch {
                usbDacManager.routeStatus.collect { route ->
                    if (player.playWhenReady && usbRouteMismatchRequiresPause(
                            route,
                            setOf(AudioDeviceInfo.TYPE_USB_DEVICE,
                                AudioDeviceInfo.TYPE_USB_HEADSET,
                                AudioDeviceInfo.TYPE_USB_ACCESSORY),
                        )) {
                        Log.w(TAG, "Ruta DAC solicitada distinta a salida observada; pausando sin fallback silencioso")
                        player.pause()
                    }
                }
            }
            controllerScope.launch {
                usbDacManager.preferredDeviceFlow.collect { preferredDevice ->
                    try {
                        player.setPreferredAudioDevice(preferredDevice)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error aplicando dispositivo de audio en ExoPlayer", e)
                        val requestedId = usbDacManager.selectedDeviceId.value
                            ?: usbDacManager.dacState.value.deviceId.takeIf { it != 0 }
                        routeObserver?.onRoutingFailed(requestedId, RouteFailure.ROUTING_REJECTED)
                        if (player.playWhenReady && requestedId != null) player.pause()
                    }
                }
            }
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionJob = controllerScope.launch {
            while (isActive) {
                _state.value = _state.value.copy(position = player.currentPosition)
                delay(250)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    @Suppress("unused")
    fun getRepository(): LocalMediaRepository = repository

    @Suppress("unused")
    fun getExoPlayer(): ExoPlayer = player

    private fun resolveUri(track: Track): Uri {
        val path = track.filepath
        return when {
            path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://") ->
                path.toUri()
            path.startsWith("/") -> Uri.fromFile(File(path))
            else -> path.toUri()
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
        val entries = tracks.mapIndexed { idx, t -> org.michimusic.core.models.QueueEntry(track = t, position = idx) }
        val state = PlayerState(
            currentTrack = tracks.getOrNull(startIndex),
            queue = tracks,
            queueEntries = entries,
            queueIndex = startIndex,
            isPlaying = false,
            duration = tracks.getOrNull(startIndex)?.duration ?: 0L,
            playbackStatus = PlaybackStatus.Buffering,
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
        val newEntries = _state.value.queueEntries + org.michimusic.core.models.QueueEntry(track = track, position = newQueue.size - 1)
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
        _state.value = _state.value.copy(queue = newQueue, queueEntries = newEntries)
    }

    fun playNext(track: Track) {
        val currentQueue = _state.value.queue.toMutableList()
        val currentEntries = _state.value.queueEntries.toMutableList()
        val insertIndex = (_state.value.queueIndex + 1).coerceIn(0, currentQueue.size)
        currentQueue.add(insertIndex, track)
        currentEntries.add(insertIndex, org.michimusic.core.models.QueueEntry(track = track, position = insertIndex))
        val reindexedEntries = currentEntries.mapIndexed { idx, entry -> entry.copy(position = idx) }
        player.addMediaItem(
            insertIndex,
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
        _state.value = _state.value.copy(queue = currentQueue, queueEntries = reindexedEntries)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _state.value.queue.toMutableList()
        val currentEntries = _state.value.queueEntries.toMutableList()
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices && fromIndex != toIndex) {
            val item = currentQueue.removeAt(fromIndex)
            val entry = currentEntries.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            currentEntries.add(toIndex, entry)
            val reindexedEntries = currentEntries.mapIndexed { idx, e -> e.copy(position = idx) }
            player.moveMediaItem(fromIndex, toIndex)
            val newCurrentIndex = when (_state.value.queueIndex) {
                fromIndex -> toIndex
                in (fromIndex + 1)..toIndex -> _state.value.queueIndex - 1
                in toIndex until fromIndex -> _state.value.queueIndex + 1
                else -> _state.value.queueIndex
            }
            _state.value = _state.value.copy(queue = currentQueue, queueEntries = reindexedEntries, queueIndex = newCurrentIndex)
        }
    }

    fun removeFromQueue(index: Int) {
        val newQueue = _state.value.queue.toMutableList()
        val newEntries = _state.value.queueEntries.toMutableList()
        if (index in newQueue.indices) {
            newQueue.removeAt(index)
            newEntries.removeAt(index)
            val reindexedEntries = newEntries.mapIndexed { idx, e -> e.copy(position = idx) }
            player.removeMediaItem(index)
            _state.value = _state.value.copy(queue = newQueue, queueEntries = reindexedEntries)
        }
    }

    fun clearQueue() {
        signalTruthTracker?.onPlaybackStopped()
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

    fun release() {
        signalTruthTracker?.onPlaybackStopped()
        stopPositionUpdates()
        usbDacManager?.onUsbDisconnectedListener = null
        routeObserver?.detachAudioTrack()
        controllerJob.cancel()
        player.release()
        audioEffects?.release()
    }

    private companion object {
        fun createNoopReplayGainDao(): ReplayGainDao {
            return object : ReplayGainDao {
                override suspend fun getReplayGain(trackId: String) = null
                override suspend fun getAllReplayGains() = emptyList<ReplayGainEntity>()
                override suspend fun upsert(entity: ReplayGainEntity) {}
                override suspend fun upsertAll(entities: List<ReplayGainEntity>) {}
            }
        }
    }
}
