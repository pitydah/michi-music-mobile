package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.michimusic.mobile.playback.EndpointType
import org.michimusic.mobile.playback.PlaybackEndpoint

class MichiControlPolicyTest {
    private fun endpoint(type: EndpointType) = PlaybackEndpoint(
        id = type.name.lowercase(),
        name = type.name,
        type = type,
        isConnected = true,
    )

    @Test fun everyControllerRoleHasAnExplicitGlyph() {
        assertEquals(MichiControlGlyph.PHONE, michiControlGlyph(endpoint(EndpointType.LOCAL_PHONE)))
        assertEquals(MichiControlGlyph.DESKTOP, michiControlGlyph(endpoint(EndpointType.DESKTOP_PLAYER)))
        assertEquals(MichiControlGlyph.SERVER, michiControlGlyph(endpoint(EndpointType.SERVER)))
        assertEquals(MichiControlGlyph.RECEIVER, michiControlGlyph(endpoint(EndpointType.STREAM_RECEIVER)))
        assertEquals(MichiControlGlyph.ROOM, michiControlGlyph(endpoint(EndpointType.ROOM)))
        assertEquals(MichiControlGlyph.UNKNOWN, michiControlGlyph(endpoint(EndpointType.UNKNOWN)))
    }

    @Test fun phoneAndStreamReceiverAreNotGenericControllers() {
        val phone = michiControlGlyph(PlaybackEndpoint.LocalPhone)
        val stream = michiControlGlyph(endpoint(EndpointType.STREAM_RECEIVER))
        assertEquals(MichiControlGlyph.PHONE, phone)
        assertEquals(MichiControlGlyph.RECEIVER, stream)
        assertNotEquals(phone, stream)
        assertNotEquals(MichiControlGlyph.UNKNOWN, phone)
    }
}
