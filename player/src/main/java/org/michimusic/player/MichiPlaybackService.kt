package org.michimusic.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import kotlinx.coroutines.withContext
import org.michimusic.data.cache.ReplayGainDao

@OptIn(UnstableApi::class)
class MichiPlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var playerController: PlayerController? = null
    private lateinit var replayGainProcessor: ReplayGainAudioProcessor
    private lateinit var stateStore: PlaybackStateStore
    private val saveScopeJob = Job()
    private val saveScope = CoroutineScope(Dispatchers.IO + saveScopeJob)
    private var saveJob: Job? = null
    private val transitionEpoch = PlaybackTransitionEpoch()
    private val serviceScopeJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceScopeJob)

    override fun onCreate() {
        super.onCreate()
        try {
            // Resolve the very same Koin singleton observed by the Compose UI, or initialize fallback for tests
            replayGainProcessor = PlayerDependencies.replayGainProcessor ?: ReplayGainAudioProcessor().also {
                PlayerDependencies.replayGainProcessor = it
            }
            stateStore = PlaybackStateStore(applicationContext)

            val mode = ReplayGainConfig.getMode()
            val preAmp = ReplayGainConfig.getPreAmp()
            replayGainProcessor.configure(mode, preAmp)

            val replayGainDao = PlayerDependencies.replayGainDao
            val audioEffects = PlayerDependencies.audioEffects
            val usbDacManager = PlayerDependencies.usbDacManager
            val rtpTap = RtpPcmAudioTap()
            PlayerDependencies.rtpPcmAudioTap = rtpTap

            val modeManager = PlayerDependencies.audioModeManager ?: AudioModeManager(
                context = this,
                audioEffects = audioEffects,
                replayGainProcessor = replayGainProcessor,
                usbDacManager = usbDacManager,
            ).also { PlayerDependencies.audioModeManager = it }

            val truthTracker = PlayerDependencies.signalTruthTracker ?: SignalTruthTracker(
                audioModeManager = modeManager,
                audioEffects = audioEffects,
                replayGainProcessor = replayGainProcessor,
                usbDacManager = usbDacManager,
            ).also { PlayerDependencies.signalTruthTracker = it }

            truthTracker.clearPlayback(resetInstrumentation = true)
            modeManager.applyMode(modeManager.currentMode.value)

            // These collectors belong to the service lifetime, not a UI screen.
            serviceScope.launch {
                usbDacManager?.routeStatus?.collect {
                    modeManager.applyMode(modeManager.currentMode.value)
                    truthTracker.recompute()
                }
            }
            serviceScope.launch {
                modeManager.currentMode.collect { truthTracker.recompute() }
            }
            serviceScope.launch {
                audioEffects?.effectsState?.collect { truthTracker.recompute() }
            }

            serviceScope.launch {
                rtpTap.currentFormat.collect { pcm ->
                    truthTracker.onPcmFormatDecoded(pcm)
                }
            }

            val controller = PlayerController(
                this,
                listOf(replayGainProcessor, rtpTap),
                replayGainDao,
                audioEffects,
                usbDacManager,
                truthTracker,
            )
            val exoPlayer = controller.getExoPlayer()
            playerController = controller
            player = exoPlayer

            val libraryProvider = LibraryProvider(this, controller.getRepository())
            val callback = MichiMediaLibrarySessionCallback(libraryProvider, stateStore, saveScope)

            setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

            mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, callback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        packageManager?.getLaunchIntentForPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()

            saveScope.launch {
                restorePlaybackState(libraryProvider)
            }

            exoPlayer.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val generation = transitionEpoch.advance()
                    val songId = mediaItem?.mediaId?.let { LibraryProvider.extractSongId(it) }
                    // Only the transition invalidates measurements; metadata lookup must not.
                    truthTracker.onTrackChanged(null)
                    truthTracker.setPlaybackActive(exoPlayer.isPlaying)
                    replayGainProcessor.onSongChanged(null)
                    truthTracker.recompute()
                    if (songId != null) {
                        saveScope.launch {
                            val track = controller.getRepository().loadTracks()
                                .firstOrNull { it.id == songId }
                            withContext(Dispatchers.Main) {
                                // An older asynchronous lookup must never label the next song.
                                if (transitionEpoch.isCurrent(generation) &&
                                    exoPlayer.currentMediaItem?.mediaId == mediaItem?.mediaId) {
                                    replayGainProcessor.onSongChanged(track)
                                    truthTracker.recompute()
                                }
                            }
                        }
                    }
                    deferSave()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) deferSave()
                    if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                        // Reject metadata loaded after the queue has stopped/ended.
                        transitionEpoch.advance()
                        replayGainProcessor.onSongChanged(null)
                        truthTracker.onPlaybackStopped()
                    }
                }

                private var consecutiveErrorCount = 0
                private var playbackSuccessJob: kotlinx.coroutines.Job? = null
                private var lastFailedTrackId: String? = null

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playbackSuccessJob?.cancel()
                    truthTracker.setPlaybackActive(isPlaying)
                    if (isPlaying) {
                        // Avoid resetting consecutiveErrorCount on transient isPlaying transitions.
                        // Only reset if playback sustains on the main thread for at least 2.5 seconds without error.
                        playbackSuccessJob = serviceScope.launch {
                            kotlinx.coroutines.delay(2500L)
                            consecutiveErrorCount = 0
                            lastFailedTrackId = null
                        }
                    } else {
                        deferSave()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playbackSuccessJob?.cancel()
                    val currentTrackId = exoPlayer.currentMediaItem?.mediaId
                    consecutiveErrorCount++
                    lastFailedTrackId = currentTrackId
                    android.util.Log.e(
                        "MichiPlaybackService",
                        "onPlayerError errorCode=${error.errorCode} message=${error.message} consecutiveErrors=$consecutiveErrorCount " +
                            "mediaId=$currentTrackId mediaItemCount=${exoPlayer.mediaItemCount}",
                    )
                    // If track is unplayable/missing/corrupted and there are remaining items in the queue, skip forward safely
                    // Stop skipping if 3 consecutive tracks failed to prevent runaway battery/CPU loops
                    serviceScope.launch {
                        if (consecutiveErrorCount <= 3 && exoPlayer.hasNextMediaItem()) {
                            exoPlayer.seekToNextMediaItem()
                            exoPlayer.prepare()
                            exoPlayer.play()
                        } else {
                            android.util.Log.w(
                                "MichiPlaybackService",
                                "Deteniendo reproducción: consecutiveErrors=$consecutiveErrorCount hasNext=${exoPlayer.hasNextMediaItem()}"
                            )
                            exoPlayer.pause()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("MichiPlaybackService", "No se pudo iniciar servicio de audio", e)
            mediaLibrarySession?.release()
            mediaLibrarySession = null
            playerController?.release()
            playerController = null
            player = null
            stopSelf() // fail closed instead of keeping a broken service visible
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
        transitionEpoch.advance() // invalidate in-flight metadata lookups
        saveNow()
        saveJob?.cancel()
        saveScopeJob.cancel()
        serviceScopeJob.cancel()
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        playerController?.release()
        playerController = null
        player = null
        PlayerDependencies.stopPcmStreaming()
        PlayerDependencies.rtpPcmAudioTap = null
        PlayerDependencies.signalTruthTracker?.clearPlayback(resetInstrumentation = true)
        PlayerDependencies.replayGainProcessor?.onSongChanged(null)
        super.onDestroy()
    }

    private suspend fun restorePlaybackState(provider: LibraryProvider) {
        val saved = stateStore.restore()
        if (saved.mediaIds.isEmpty()) return
        provider.refresh()
        val items = saved.mediaIds.mapNotNull { provider.getItem(it) }
        if (items.isEmpty()) return
        val p = player ?: return
        val resolved = provider.resolveForPlayback(items) ?: return
        val startIndex = saved.startIndex.coerceIn(0, resolved.lastIndex)
        withContext(Dispatchers.Main) {
            p.setMediaItems(resolved, startIndex, saved.positionMs)
            p.repeatMode = saved.repeatMode
            p.shuffleModeEnabled = saved.shuffleMode
            p.prepare()
        }
    }

    private fun deferSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(3000)
            // saveScope runs on Dispatchers.IO, but ExoPlayer/Player must only be touched from
            // its application thread (main). Reading the snapshot has to happen on Main; only
            // the actual persistence (SharedPreferences write) is left on this IO-scoped
            // coroutine. See snapshotState()/saveNow() below.
            val state = withContext(Dispatchers.Main) { snapshotState() } ?: return@launch
            stateStore.save(state)
        }
    }

    // Called directly from onTaskRemoved()/onDestroy(), both of which the Android framework
    // already invokes on the main thread - so this stays a plain synchronous read+persist
    // for them, with no dispatcher hop needed.
    private fun saveNow() {
        val state = snapshotState() ?: return
        stateStore.save(state)
    }

    // The only part of saving that touches the Player. Must always be called from the
    // player's application thread (main) - see the callers above for how each one guarantees
    // that.
    private fun snapshotState(): PlaybackStateStore.SavedState? {
        val p = player ?: return null
        val count = p.mediaItemCount
        if (count == 0) return null
        val ids = (0 until count).map { p.getMediaItemAt(it).mediaId }
        return PlaybackStateStore.SavedState(
            mediaIds = ids,
            startIndex = p.currentMediaItemIndex,
            positionMs = p.currentPosition,
            repeatMode = p.repeatMode,
            shuffleMode = p.shuffleModeEnabled,
        )
    }
}
