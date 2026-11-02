package org.michimusic.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.playback.EndpointType
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.RemoteEndpointPreview

class AeroM60EditorialTest {
    @Test fun compactWidthStacksEditorialActions() {
        assertTrue(aeroM60StackActions(280f, 1f))
        assertTrue(aeroM60StackActions(329.9f, 1f))
        assertFalse(aeroM60StackActions(330f, 1f))
    }

    @Test fun signalFooterStacksOnNarrowViewportsOrLargeFonts() {
        assertTrue(aeroM60StackSignal(320f, 1f))
        assertFalse(aeroM60StackSignal(360f, 1f))
        assertTrue(aeroM60StackSignal(480f, 2f))
    }

    @Test fun qualityBadgeNeverInfersLosslessOrBitperfect() {
        org.junit.Assert.assertEquals("Michi Music", aeroNowPlayingQualityBadge(false))
        org.junit.Assert.assertEquals("PCM decodificado", aeroNowPlayingQualityBadge(true))
    }

    @Test fun largeFontStacksEvenOnWideScreens() {
        assertTrue(aeroM60StackActions(600f, 1.3f))
        assertFalse(aeroM60StackActions(600f, 1.2f))
    }

    private fun remote(type: EndpointType, connected: Boolean, caps: Set<String> = emptySet()) =
        PlaybackEndpoint("michi-node", "Michi Server", type,
            isConnected = connected, capabilities = caps)

    @Test fun onlyReachableControlHostsAreEnabled() {
        assertTrue(isMichiControlTargetAvailable(PlaybackEndpoint.LocalPhone))
        assertTrue(isMichiControlTargetAvailable(remote(EndpointType.SERVER, true, setOf("REMOTE_CONTROL"))))
        assertTrue(isMichiControlTargetAvailable(remote(EndpointType.DESKTOP_PLAYER, true, setOf("REMOTE_CONTROL"))))
        assertFalse(isMichiControlTargetAvailable(remote(EndpointType.SERVER, false, setOf("REMOTE_CONTROL"))))
        assertFalse(isMichiControlTargetAvailable(remote(EndpointType.SERVER, true)))
        assertFalse(isMichiControlTargetAvailable(remote(EndpointType.STREAM_RECEIVER, true, setOf("AUDIO_OUTPUT"))))
        assertFalse(isMichiControlTargetAvailable(remote(EndpointType.UNKNOWN, true)))
    }

    @Test fun inactiveHostDoesNotBorrowActiveTrack() {
        val host = remote(EndpointType.SERVER, true)
        val text = michiControlEndpointSubtitle(host, "local_phone", "Baby, Come to Me", false)
        assertTrue(text.contains("consultando"))
        assertFalse(text.contains("Baby, Come to Me"))
        assertTrue(michiControlEndpointSubtitle(host, host.id, "Baby, Come to Me", false)
            .contains("Pausado · Baby, Come to Me"))
        val preview = RemoteEndpointPreview(host.id, "Remote Session", "playing", 123L)
        assertTrue(michiControlEndpointSubtitle(host, "local_phone", "Local Session", true, preview)
            .contains("Reproduciendo · Remote Session"))
        assertFalse(michiControlEndpointSubtitle(host, "local_phone", "Local Session", true, preview)
            .contains("Local Session"))
        val offline = preview.copy(fetchedAtMs = 0L, title = null)
        assertTrue(michiControlEndpointSubtitle(host, "local_phone", null, false, offline)
            .contains("No se pudo consultar"))
    }
}
