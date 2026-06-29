package org.michimusic.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.michimusic.data.cache.ReplayGainDao

@OptIn(UnstableApi::class)
class MichiPlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var playerController: PlayerController? = null
    private lateinit var replayGainProcessor: ReplayGainAudioProcessor
    private lateinit var stateStore: PlaybackStateStore
    private val saveScope = CoroutineScope(Dispatchers.IO + Job())
    private var saveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        try {
            replayGainProcessor = ReplayGainAudioProcessor()
            stateStore = PlaybackStateStore(applicationContext)

            val mode = ReplayGainConfig.getMode()
            val preAmp = ReplayGainConfig.getPreAmp()
            replayGainProcessor.configure(mode, preAmp)

            val replayGainDao = PlayerDependencies.replayGainDao
            val controller = PlayerController(this, listOf(replayGainProcessor), replayGainDao)
            playerController = controller
            player = controller.getExoPlayer()

            val libraryProvider = LibraryProvider(this, controller.getRepository())
            val callback = MichiMediaLibrarySessionCallback(libraryProvider, stateStore)

            setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

            mediaLibrarySession = MediaLibrarySession.Builder(this, player!!, callback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        packageManager?.getLaunchIntentForPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()

            restorePlaybackState(libraryProvider)

            player!!.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val songId = mediaItem?.mediaId?.let { LibraryProvider.extractSongId(it) }
                    if (songId != null) {
                        val track = controller.getRepository().loadTracks()
                            .firstOrNull { it.id == songId }
                        replayGainProcessor.onSongChanged(track)
                    }
                    deferSave()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) deferSave()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) deferSave()
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("MichiPlaybackService", "Error al iniciar servicio", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveNow()
        val shouldStop = mediaLibrarySession?.player?.playWhenReady != true
        if (shouldStop) stopSelf()
    }

    override fun onDestroy() {
        saveNow()
        saveJob?.cancel()
        playerController?.release()
        playerController = null
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    private fun restorePlaybackState(provider: LibraryProvider) {
        val saved = stateStore.restore()
        if (saved.mediaIds.isEmpty()) return
        provider.refresh()
        val items = saved.mediaIds.mapNotNull { provider.getItem(it) }
        if (items.isEmpty()) return
        val p = player ?: return
        val resolved = provider.resolveForPlayback(items) ?: return
        val startIndex = saved.startIndex.coerceIn(0, resolved.lastIndex)
        p.setMediaItems(resolved, startIndex, saved.positionMs)
        p.repeatMode = saved.repeatMode
        p.shuffleModeEnabled = saved.shuffleMode
        p.prepare()
    }

    private fun deferSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(3000)
            saveNow()
        }
    }

    private fun saveNow() {
        val p = player ?: return
        val count = p.mediaItemCount
        if (count == 0) return
        val ids = (0 until count).map { p.getMediaItemAt(it).mediaId }
        val state = PlaybackStateStore.SavedState(
            mediaIds = ids,
            startIndex = p.currentMediaItemIndex,
            positionMs = p.currentPosition,
            repeatMode = p.repeatMode,
            shuffleMode = p.shuffleModeEnabled,
        )
        stateStore.save(state)
    }
}
