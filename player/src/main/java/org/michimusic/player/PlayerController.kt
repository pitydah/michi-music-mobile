package org.michimusic.player

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.Track
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.data.cache.ReplayGainEntity
import org.michimusic.data.repository.LocalMediaRepository
import java.io.File

class PlayerController(
    context: Context,
    audioProcessors: List<AudioProcessor> = emptyList(),
    replayGainDao: ReplayGainDao? = null,
) {

    private val repository = replayGainDao?.let { LocalMediaRepository(context, it) }
        ?: LocalMediaRepository(context, createNoopReplayGainDao())

    private val audioSink = if (audioProcessors.isNotEmpty()) {
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(audioProcessors.toTypedArray())
            .build()
    } else {
        DefaultAudioSink.Builder(context).build()
    }

    private val renderersFactory = RenderersFactory { handler, _, audioListener, _, _ ->
        arrayOf(
            MediaCodecAudioRenderer(
                context,
                MediaCodecSelector.DEFAULT,
                handler,
                audioListener,
                audioSink,
            ),
        )
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var positionJob: kotlinx.coroutines.Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val queue = _state.value.queue
                val index = player.currentMediaItemIndex
                if (index in queue.indices) {
                    _state.value = _state.value.copy(
                        currentTrack = queue[index],
                        queueIndex = index,
                        position = 0L,
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

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            while (isActive) {
                _state.value = _state.value.copy(position = player.currentPosition)
                kotlinx.coroutines.delay(250)
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
        player.release()
    }

    private companion object {
        fun createNoopReplayGainDao(): ReplayGainDao {
            return object : ReplayGainDao {
                override suspend fun getReplayGain(trackId: String) = null
                override suspend fun upsert(entity: ReplayGainEntity) {}
                override suspend fun upsertAll(entities: List<ReplayGainEntity>) {}
            }
        }
    }
}
