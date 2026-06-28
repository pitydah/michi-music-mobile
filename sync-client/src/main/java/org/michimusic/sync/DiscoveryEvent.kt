package org.michimusic.sync

import org.michimusic.core.models.DiscoveredPeer

sealed class DiscoveryEvent {
    data class PeerFound(val peer: DiscoveredPeer) : DiscoveryEvent()
    data class PeerLost(val alias: String) : DiscoveryEvent()
}
