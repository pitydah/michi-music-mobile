package org.michimusic.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import org.michimusic.player.AudioController
import org.michimusic.player.ReplayGainConfig

val playerModule = module {
    single {
        ReplayGainConfig.init(androidContext())
        AudioController(androidContext(), CoroutineScope(Dispatchers.Main + SupervisorJob()))
    }
    single { org.michimusic.player.MichiAudioEffects(androidContext()) }
    // Playback and UI resolve one processor; only the service owns its Media3 graph.
    single { org.michimusic.player.ReplayGainAudioProcessor() }
    single { org.michimusic.player.UsbDacManager(androidContext()) }
    single {
        org.michimusic.player.AudioModeManager(
            context = androidContext(),
            audioEffects = get(),
            replayGainProcessor = get(),
            usbDacManager = get(),
        )
    }
    single {
        org.michimusic.player.SignalTruthTracker(
            audioModeManager = get(),
            audioEffects = get(),
            replayGainProcessor = get(),
            usbDacManager = get(),
        )
    }
    single {
        org.michimusic.mobile.playback.PlaybackSessionManager(
            audioController = get(),
            connectionManager = get(),
            linkDiscovery = get(),
            registry = get(),
        )
    }
}
