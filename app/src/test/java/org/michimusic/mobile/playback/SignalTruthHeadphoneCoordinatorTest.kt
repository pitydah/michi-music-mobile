package org.michimusic.mobile.playback

import org.junit.Test
import org.junit.Assert.assertEquals

class SignalTruthHeadphoneCoordinatorTest {
    private class Engine(var snap: HeadphoneQuickSnapshot? =
        HeadphoneQuickSnapshot("Neutral", false, false, 6), var accepts: Boolean=true)
        : HeadphoneQuickEngine {
        var changes=0
        var disables=0
        override fun snapshot()=snap
        override fun selectPreset(preset: String): Boolean {
            changes++
            if (!accepts) return false
            snap=snap?.copy(preset=preset, legacyBandsTruncated=false, bandsCount=6)
            return true
        }
        override fun disableProfile(): Boolean {
            disables++
            if (!accepts) return false
            snap=snap?.copy(enabled=false)
            return true
        }
    }
    @Test fun changingPresetIsConfigurationNotActivation() {
        val e=Engine()
        val r=SignalTruthHeadphoneCoordinator(e) { true }
            .choosePreset(SignalDestination.LOCAL,"Warm")
        assertEquals(HeadphoneQuickStatus.CONFIGURED,r.status)
        assertEquals("Warm",e.snap?.preset)
        assertEquals(false,e.snap?.enabled)
    }
    @Test fun customPresetCannotOverwriteBands() {
        val e=Engine()
        val r=SignalTruthHeadphoneCoordinator(e) { true }
            .choosePreset(SignalDestination.LOCAL,"Custom")
        assertEquals(HeadphoneQuickStatus.INVALID_PRESET,r.status)
        assertEquals(0,e.changes)
    }
    @Test fun remoteHostNeverWritesLocalProfile() {
        val e=Engine()
        val r=SignalTruthHeadphoneCoordinator(e) { true }
            .choosePreset(SignalDestination.REMOTE_HOST,"Voice")
        assertEquals(HeadphoneQuickStatus.WRONG_DESTINATION,r.status)
        assertEquals(0,e.changes)
    }
    @Test fun streamIsReadOnlyForLocalPeq() {
        val e=Engine()
        val r=SignalTruthHeadphoneCoordinator(e) { true }
            .choosePreset(SignalDestination.MICHI_STREAM,"Voice")
        assertEquals(HeadphoneQuickStatus.WRONG_DESTINATION,r.status)
        assertEquals(0,e.changes)
    }
    @Test fun revokedAuthorityBlocksStaleTap() {
        val e=Engine()
        val r=SignalTruthHeadphoneCoordinator(e) { false }
            .choosePreset(SignalDestination.LOCAL,"Voice")
        assertEquals(HeadphoneQuickStatus.WRONG_DESTINATION,r.status)
        assertEquals(0,e.changes)
    }
    @Test fun incompleteLegacyProfileIsReplacedOnlyAfterExplicitPresetSelection() {
        val e=Engine(HeadphoneQuickSnapshot("Custom",false,true,3))
        val r=SignalTruthHeadphoneCoordinator(e) { true }
            .choosePreset(SignalDestination.LOCAL,"Neutral")
        assertEquals(HeadphoneQuickStatus.CONFIGURED,r.status)
        assertEquals(6,e.snap?.bandsCount)
    }
    @Test fun failedEnginePreservesExistingPreset() {
        val e=Engine(accepts=false)
        val r=SignalTruthHeadphoneCoordinator(e) { true }
            .choosePreset(SignalDestination.LOCAL,"Voice")
        assertEquals(HeadphoneQuickStatus.REJECTED,r.status)
        assertEquals("Neutral",e.snap?.preset)
    }
    @Test fun disabledProfileCanBeDisabledWithoutConnection() {
        val e=Engine()
        val r=SignalTruthHeadphoneCoordinator(e) { true }.disable(SignalDestination.LOCAL)
        assertEquals(HeadphoneQuickStatus.DISABLED,r.status)
        assertEquals(0,e.disables)
    }
    @Test fun previouslyEnabledProfileCanBeDisabledWithoutRoute() {
        val e=Engine(HeadphoneQuickSnapshot("Warm",true,false,6))
        val r=SignalTruthHeadphoneCoordinator(e) { true }.disable(SignalDestination.LOCAL)
        assertEquals(HeadphoneQuickStatus.DISABLED,r.status)
        assertEquals(1,e.disables)
        assertEquals(false,e.snap?.enabled)
    }
    @Test fun remoteCannotDisableLocalProfile() {
        val e=Engine(HeadphoneQuickSnapshot("Warm",true,false,6))
        val r=SignalTruthHeadphoneCoordinator(e) { true }.disable(SignalDestination.REMOTE_HOST)
        assertEquals(HeadphoneQuickStatus.WRONG_DESTINATION,r.status)
        assertEquals(0,e.disables)
    }
    @Test fun noStoreIsUnavailable() {
        val e=Engine(null)
        val r=SignalTruthHeadphoneCoordinator(e) { true }
            .choosePreset(SignalDestination.LOCAL,"Neutral")
        assertEquals(HeadphoneQuickStatus.UNAVAILABLE,r.status)
    }
}
