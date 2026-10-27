package org.michimusic.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerEndpointPolicyTest {
    @Test fun sameHostAndPortPasses() = assertTrue(sameDiscoveredEndpoint("192.168.1.9", 9090, "192.168.1.9", 9090))
    @Test fun differentPortFails() = assertFalse(sameDiscoveredEndpoint("192.168.1.9", 9090, "192.168.1.9", 8400))
    @Test fun differentIpFails() = assertFalse(sameDiscoveredEndpoint("192.168.1.9", 9090, "192.168.1.8", 9090))
    @Test fun invalidPortFails() = assertFalse(sameDiscoveredEndpoint("host", 0, "host", 0))
    @Test fun bracketedIpv6Match() = assertTrue(sameDiscoveredEndpoint("[fe80::a]", 9090, "fe80::a", 9090))
    @Test fun unknownTypeNeverEnablesButton() = assertFalse(allowLegacyReceiverButton(null, false))
    @Test fun streamTypeNeverEnablesButton() = assertFalse(allowLegacyReceiverButton("michi_stream_standard", true))
    @Test fun legacyReceiverAllowedOnlyWhenDiscovered() = assertTrue(allowLegacyReceiverButton("receiver", true))
    @Test fun legacyReceiverManualUnknownBlocked() = assertFalse(allowLegacyReceiverButton("receiver", false))
    @Test fun reusedIpWithDifferentMichiIdDoesNotImplyPresence() = assertFalse(
        matchesObservedPeer("known-A", "192.168.1.10", 8400, "observed-B", "192.168.1.10", 8400))
    @Test fun sameMichiIdCanFollowDhcpAddressChange() = assertTrue(
        matchesObservedPeer("node-1", "192.168.1.10", 8400, "node-1", "192.168.1.25", 8400))
    @Test fun missingObservedIdUsesOnlyExactEndpoint() = assertTrue(
        matchesObservedPeer("node-1", "192.168.1.10", 8400, "", "192.168.1.10", 8400))
    @Test fun noIdAndDifferentEndpointNeverMatches() = assertFalse(
        matchesObservedPeer("", "192.168.1.10", 8400, "", "192.168.1.11", 8400))
}
