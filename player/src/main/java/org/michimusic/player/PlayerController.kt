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

    private val audioTrackProvider = DefaultAudioSink.AudioTrackProvider { config, attr, sessionId ->
        val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attr, sessionId)
        routeObserver?.attachAudioTrack(track)
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
        Log.w(TAG, "Failed to create custom AudioSink with processors; falling back to default", e)
        DefaultAudioSink.Builder(context)
            .setAudioTrackProvider(audioTrackProvider)
            .build()
    }

    private val audioRendererEventListener = object : androidx.media3.exoplayer.audio.AudioRendererEventListener {
        override fun onAudioInputFormatChanged(
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            signalTruthTracker?.onDecoderInputFormat(format)
        }

        override fun onAudioTrackInitialized(audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
            signalTruthTracker?.onAudioTrackInitialized(
                sampleRate = audioTrackConfig.sampleRate,
                channelCount = java.lang.Integer.bitCount(audioTrackConfig.channelConfig),
                encoding = audioTrackConfig.encoding,
            )
        }
    }

    private val renderersFactory = RenderersFactory { handler, _, _, _, _ ->
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
            Log.e(TAG, "Failed to create MediaCodecAudioRenderer, falling back to empty renderers", e)
            emptyArray()
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
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val queue = _state.value.queue
                val index = player.currentMediaItemIndex
                if (index in queue.indices) {
                    val track = queue[index]
                    _state.value = _state.value.copy(
                        currentTrack = track,
                        queueIndex = index,
                        position = 0L,
                    )
                    signalTruthTracker?.onTrackChanged(track)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
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
                    _state.value = _state.value.copy(playbackStatus = PlaybackStatus.Ended, isPlaying = false)
                } else if (playbackState == Player.STATE_IDLE) {
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
                if (player.isPlaying) {
                    Log.i(TAG, "USB desconectado durante reproducción; pausando para evitar audio por altavoz")
                    pause()
                }
            }
            controllerScope.launch {
                usbDacManager.preferredDeviceFlow.collect { preferredDevice ->
                    try {
                        player.setPreferredAudioDevice(preferredDevice)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error aplicando dispositivo de audio en ExoPlayer", e)
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

    fun release() {
        stopPositionUpdates()
        usbDacManager?.onUsbDisconnectedListener = null
        routeObserver?.detachAudioTrack()
        controllerJob.cancel()
        player.release()
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
