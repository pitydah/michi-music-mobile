package org.michimusic.mobile.playback

import org.junit.Test
import org.junit.Assert.assertEquals
import org.michimusic.core.models.AudioProcessingMode

class SignalTruthAudioModeCoordinatorTest {
    private class Engine(var mode: AudioProcessingMode? = AudioProcessingMode.DSP,
                         var accepts: Boolean = true) : AudioModeQuickEngine {
        var calls = 0
        override fun currentMode(): AudioProcessingMode? = mode
        override fun configure(mode: AudioProcessingMode): Boolean {
            calls++
            if (!accepts) return false
            this.mode = mode
            return true
        }
    }
    @Test fun setsTransparentViaExistingEngine() {
        val engine=Engine()
        val result=SignalTruthAudioModeCoordinator(engine) { true }
            .choose(SignalDestination.LOCAL, AudioProcessingMode.TRANSPARENT)
        assertEquals(AudioModeQuickStatus.CONFIGURED, result.status)
        assertEquals(AudioProcessingMode.TRANSPARENT, engine.mode)
        assertEquals(1, engine.calls)
    }
    @Test fun unchangedModeIsIdempotent() {
        val engine=Engine()
        val result=SignalTruthAudioModeCoordinator(engine) { true }
            .choose(SignalDestination.LOCAL, AudioProcessingMode.DSP)
        assertEquals(AudioModeQuickStatus.CONFIGURED, result.status)
        assertEquals(0, engine.calls)
    }
    @Test fun remoteHostRejectedWithoutEngineCalls() {
        val engine=Engine()
        val result=SignalTruthAudioModeCoordinator(engine) { true }
            .choose(SignalDestination.REMOTE_HOST, AudioProcessingMode.AUTO)
        assertEquals(AudioModeQuickStatus.WRONG_DESTINATION, result.status)
        assertEquals(0, engine.calls)
    }
    @Test fun streamRejectedWithoutEngineCalls() {
        val engine=Engine()
        val result=SignalTruthAudioModeCoordinator(engine) { true }
            .choose(SignalDestination.MICHI_STREAM, AudioProcessingMode.DSP)
        assertEquals(AudioModeQuickStatus.WRONG_DESTINATION, result.status)
        assertEquals(0, engine.calls)
    }
    @Test fun revokedAuthorityIsFailClosed() {
        val engine=Engine()
        val result=SignalTruthAudioModeCoordinator(engine) { false }
            .choose(SignalDestination.LOCAL, AudioProcessingMode.TRANSPARENT)
        assertEquals(AudioModeQuickStatus.WRONG_DESTINATION, result.status)
        assertEquals(0, engine.calls)
    }
    @Test fun missingAudioModeManagerIsNotConfigured() {
        val result=SignalTruthAudioModeCoordinator(Engine(null)) { true }
            .choose(SignalDestination.LOCAL, AudioProcessingMode.AUTO)
        assertEquals(AudioModeQuickStatus.UNAVAILABLE, result.status)
    }
    @Test fun rejectionIsNotMisreported() {
        val result=SignalTruthAudioModeCoordinator(Engine(accepts=false)) { true }
            .choose(SignalDestination.LOCAL, AudioProcessingMode.AUTO)
        assertEquals(AudioModeQuickStatus.REJECTED, result.status)
    }
}
