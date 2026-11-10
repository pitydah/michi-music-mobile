package org.michimusic.mobile.ui.components

import org.michimusic.mobile.playback.EndpointType
import org.michimusic.mobile.playback.PlaybackEndpoint

/**
 * Controller-role glyph. Michi Control describes which device controls the
 * session, never which physical audio output is active.
 */
enum class MichiControlGlyph { PHONE, DESKTOP, SERVER, RECEIVER, ROOM, UNKNOWN }

fun michiControlGlyph(endpoint: PlaybackEndpoint): MichiControlGlyph = when (endpoint.type) {
    EndpointType.LOCAL_PHONE -> MichiControlGlyph.PHONE
    EndpointType.DESKTOP_PLAYER -> MichiControlGlyph.DESKTOP
    EndpointType.SERVER -> MichiControlGlyph.SERVER
    EndpointType.STREAM_RECEIVER -> MichiControlGlyph.RECEIVER
    EndpointType.ROOM -> MichiControlGlyph.ROOM
    EndpointType.UNKNOWN -> MichiControlGlyph.UNKNOWN
}
