package org.michimusic.mobile.playback

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import android.media.AudioDeviceInfo
import org.michimusic.core.models.AudioProcessingMode
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.OutputDescriptor

class SignalTruthHeadphoneCoordinatorTest {
    private class Engine(var snap: HeadphoneQuickSnapshot? =
        HeadphoneQuickSnapshot("Neutral", false, false, 6), var accepts: Boolean=true)
        : HeadphoneQuickEngine {
        var changes=0
        var disables=0
        var enableCalls=0
        override fun snapshot()=snap
        override fun selectPreset(preset: String): Boolean {
            changes++
            if (!accepts) return false
            snap=snap?.copy(preset=preset, legacyBandsTruncated=false, bandsCount=6)
            return true
        }
        override fun enableForRoute(deviceId: Int, observedAtMs: Long): Boolean {
            enableCalls++
            if (!accepts) return false
            snap = snap?.copy(enabled = true)
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

    private fun observed(id: Int, type: Int): AudioRouteStatus.Confirmed =
        AudioRouteStatus.Confirmed(OutputDescriptor(id, "Salida de prueba", type), 1234L)

    @Test fun speakerIsNeverEligibleForPeqActivation() {
        val engine = Engine()
        val speaker = observed(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        assertFalse(SignalTruthHeadphoneRoutePolicy.eligible(speaker, AudioProcessingMode.DSP, engine.snap))
        val result = SignalTruthHeadphoneCoordinator(engine, currentRoute = { speaker },
            hasLocalAuthority = { true }).enableOnObservedRoute(SignalDestination.LOCAL, AudioProcessingMode.DSP)
        assertEquals(HeadphoneQuickStatus.REJECTED, result.status)
        assertEquals(0, engine.enableCalls)
    }

    @Test fun requestedRouteCannotEnablePeq() {
        val engine = Engine()
        val requested = AudioRouteStatus.Requested(
            OutputDescriptor(55, "USB", AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse(SignalTruthHeadphoneRoutePolicy.eligible(requested, AudioProcessingMode.DSP, engine.snap))
        val result = SignalTruthHeadphoneCoordinator(engine, currentRoute = { requested },
            hasLocalAuthority = { true }).enableOnObservedRoute(SignalDestination.LOCAL, AudioProcessingMode.DSP)
        assertEquals(HeadphoneQuickStatus.UNAVAILABLE, result.status)
        assertEquals(0, engine.enableCalls)
    }

    @Test fun onlyConfirmedHeadphoneInDspMayEnableProfile() {
        val engine = Engine()
        val route = observed(77, AudioDeviceInfo.TYPE_USB_HEADSET)
        assertTrue(SignalTruthHeadphoneRoutePolicy.eligible(route, AudioProcessingMode.DSP, engine.snap))
        val coordinator = SignalTruthHeadphoneCoordinator(engine, currentRoute = { route },
            hasLocalAuthority = { true })
        assertEquals(HeadphoneQuickStatus.CONFIGURED,
            coordinator.enableOnObservedRoute(SignalDestination.LOCAL, AudioProcessingMode.DSP).status)
        assertEquals(true, engine.snap?.enabled)
        assertEquals(1, engine.enableCalls)
    }

    @Test fun transparentModeCannotEnableProfile() {
        val engine = Engine()
        val route = observed(77, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        assertFalse(SignalTruthHeadphoneRoutePolicy.eligible(route, AudioProcessingMode.TRANSPARENT, engine.snap))
        val coordinator = SignalTruthHeadphoneCoordinator(engine, currentRoute = { route },
            hasLocalAuthority = { true })
        assertEquals(HeadphoneQuickStatus.REJECTED,
            coordinator.enableOnObservedRoute(SignalDestination.LOCAL, AudioProcessingMode.TRANSPARENT).status)
        assertEquals(0, engine.enableCalls)
    }

    @Test fun changingRouteDuringCommandDisablesProfile() {
        val engine = Engine()
        val expected = observed(77, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        val other = observed(79, AudioDeviceInfo.TYPE_USB_HEADSET)
        var calls = 0
        val coordinator = SignalTruthHeadphoneCoordinator(engine, currentRoute = {
            calls++
            if (calls <= 2) expected else other
        }, hasLocalAuthority = { true })
        val result = coordinator.enableOnObservedRoute(SignalDestination.LOCAL, AudioProcessingMode.DSP)
        assertEquals(HeadphoneQuickStatus.OUTPUT_CHANGED, result.status)
        assertEquals(false, engine.snap?.enabled)
        assertEquals(1, engine.disables)
    }
    @Test fun genericUsbDacIsNotProofOfHeadphones() {
        val engine = Engine()
        listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY).forEach { type ->
            val route = observed(88, type)
            assertFalse(SignalTruthHeadphoneRoutePolicy.eligible(route, AudioProcessingMode.DSP, engine.snap))
            val result = SignalTruthHeadphoneCoordinator(engine, currentRoute = { route },
                hasLocalAuthority = { true })
                .enableOnObservedRoute(SignalDestination.LOCAL, AudioProcessingMode.DSP)
            assertEquals(HeadphoneQuickStatus.REJECTED, result.status)
        }
        assertEquals(0, engine.enableCalls)
    }
}
