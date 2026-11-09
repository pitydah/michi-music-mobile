package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.player.ReplayGainMode

class SignalTruthReplayGainCoordinatorTest {
    private class Engine(var mode: ReplayGainMode? = ReplayGainMode.OFF,
                         var allow: Boolean = true) : ReplayGainQuickEngine {
        var calls = 0
        override fun currentMode(): ReplayGainMode? = mode
        override fun configure(mode: ReplayGainMode, withPreampDb: Float, withoutPreampDb: Float): Boolean {
            calls++
            if (!allow) return false
            this.mode = mode
            return true
        }
    }
    private class Store(var with: Float = 0f, var without: Float = 0f) : ReplayGainQuickStore {
        var saved: ReplayGainMode? = null
        override fun withPreampDb() = with
        override fun withoutPreampDb() = without
        override fun saveMode(mode: ReplayGainMode) { saved = mode }
    }
    @Test fun changingTrackModeUsesExistingEngineAndSavesPreference() {
        val engine = Engine()
        val store = Store(-2f, 0f)
        val result = SignalTruthReplayGainCoordinator(engine, store) { true }
            .choose(SignalDestination.LOCAL, ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.CONFIGURED, result.status)
        assertEquals(ReplayGainMode.TRACK, engine.mode)
        assertEquals(ReplayGainMode.TRACK, store.saved)
        assertEquals(1, engine.calls)
        assertTrue(result.message.contains("no equivale"))
    }
    @Test fun remoteHostNeverMutatesLocalProcessor() {
        val engine = Engine(); val store = Store()
        val result = SignalTruthReplayGainCoordinator(engine, store) { true }
            .choose(SignalDestination.REMOTE_HOST, ReplayGainMode.ALBUM)
        assertEquals(ReplayGainQuickStatus.WRONG_DESTINATION, result.status)
        assertEquals(0, engine.calls)
        assertEquals(null, store.saved)
    }
    @Test fun streamRouteIsReadOnlyUntilScopedAudioAuthorityExists() {
        val engine = Engine(); val store = Store()
        val result = SignalTruthReplayGainCoordinator(engine, store) { true }
            .choose(SignalDestination.MICHI_STREAM, ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.WRONG_DESTINATION, result.status)
        assertEquals(0, engine.calls)
    }
    @Test fun revokedLocalAuthorityRejectsStaleSheetIntent() {
        val engine = Engine(); val store = Store()
        val result = SignalTruthReplayGainCoordinator(engine, store) { false }
            .choose(SignalDestination.LOCAL, ReplayGainMode.DYNAMIC)
        assertEquals(ReplayGainQuickStatus.WRONG_DESTINATION, result.status)
        assertEquals(0, engine.calls)
    }
    @Test fun missingEngineIsNotSavedAsMode() {
        val store = Store()
        val result = SignalTruthReplayGainCoordinator(Engine(null), store) { true }
            .choose(SignalDestination.LOCAL, ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.UNAVAILABLE, result.status)
        assertEquals(null, store.saved)
    }
    @Test fun nanPreampCannotReachEngine() {
        val engine = Engine(); val store = Store(Float.NaN)
        val result = SignalTruthReplayGainCoordinator(engine, store) { true }
            .choose(SignalDestination.LOCAL, ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.INVALID_PREFERENCES, result.status)
        assertEquals(0, engine.calls)
        assertEquals(null, store.saved)
    }
    @Test fun infinityPreampCannotReachEngine() {
        val engine = Engine(); val store = Store(without = Float.POSITIVE_INFINITY)
        val result = SignalTruthReplayGainCoordinator(engine, store) { true }
            .choose(SignalDestination.LOCAL, ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.INVALID_PREFERENCES, result.status)
        assertEquals(0, engine.calls)
    }
    @Test fun failedEngineDoesNotSaveMode() {
        val engine = Engine(allow = false); val store = Store()
        val result = SignalTruthReplayGainCoordinator(engine, store) { true }
            .choose(SignalDestination.LOCAL, ReplayGainMode.TRACK)
        assertEquals(ReplayGainQuickStatus.REJECTED, result.status)
        assertEquals(null, store.saved)
        assertEquals(ReplayGainMode.OFF, engine.mode)
    }
    @Test fun unchangedModeDoesNotReconfigureEngine() {
        val engine = Engine(); val store = Store()
        val result = SignalTruthReplayGainCoordinator(engine, store) { true }
            .choose(SignalDestination.LOCAL, ReplayGainMode.OFF)
        assertEquals(ReplayGainQuickStatus.CONFIGURED, result.status)
        assertEquals(0, engine.calls)
        assertEquals(ReplayGainMode.OFF, store.saved)
    }
}
