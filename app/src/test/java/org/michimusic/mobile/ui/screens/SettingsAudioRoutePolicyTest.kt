package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.OutputDescriptor
import org.michimusic.player.RouteFailure

class SettingsAudioRoutePolicyTest {
    private fun output(name: String) = OutputDescriptor(10, name, androidType = 0)
    @Test fun confirmedNamesAndroidAsEvidence() {
        assertTrue(settingsAudioRouteSummary(AudioRouteStatus.Confirmed(output("USB DAC"), 123L))
            .contains("observada en Android"))
    }
    @Test fun requestedDoesNotClaimApplied() {
        assertTrue(settingsAudioRouteSummary(AudioRouteStatus.Requested(output("DAC"))).contains("Esperando"))
    }
    @Test fun lastObservedIsNotLive() {
        assertTrue(settingsAudioRouteSummary(AudioRouteStatus.LastObserved(output("DAC"), 13L))
            .contains("No es observación actual"))
    }
    @Test fun routedElsewhereExposesBothDevices() {
        val info = settingsAudioRouteSummary(AudioRouteStatus.RoutedElsewhere(output("USB"), output("Altavoz"), 7L))
        assertTrue(info.contains("Altavoz") && info.contains("USB"))
    }
    @Test fun failureDoesNotPromiseSuccess() {
        assertTrue(settingsAudioRouteSummary(AudioRouteStatus.Failed(4, RouteFailure.ROUTING_REJECTED))
            .contains("no pudo"))
    }
    @Test fun noDeviceIsExplicit() {
        assertTrue(settingsAudioRouteSummary(AudioRouteStatus.NoDevice).contains("No se ha observado"))
    }
    @Test fun detectionIsNotConfirmation() {
        assertTrue(settingsAudioRouteSummary(AudioRouteStatus.Detected(output("USB"))).contains("sin confirmar"))
    }
    @Test fun unknownIsHonest() {
        assertTrue(settingsAudioRouteSummary(AudioRouteStatus.Unknown("Sin AudioTrack"))
            .contains("Sin AudioTrack"))
    }
}
