package org.michimusic.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.michimusic.player.AudioModeManager
import org.michimusic.player.PlayerDependencies
import org.michimusic.player.ReplayGainAudioProcessor
import org.michimusic.player.SignalTruthTracker

/** Instrumented integration guard: the UI and playback service resolve the same authorities. */
@RunWith(AndroidJUnit4::class)
class M22PlaybackAuthorityTest {
    @Test
    fun applicationDependencyBridgeUsesKoinPlaybackSingletons() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        assertNotNull(application)
        val koin = GlobalContext.get()
        val signal = koin.get<SignalTruthTracker>()
        val mode = koin.get<AudioModeManager>()
        val replayGain = koin.get<ReplayGainAudioProcessor>()

        assertSame(signal, PlayerDependencies.signalTruthTracker)
        assertSame(mode, PlayerDependencies.audioModeManager)
        assertSame(replayGain, PlayerDependencies.replayGainProcessor)
    }
}
