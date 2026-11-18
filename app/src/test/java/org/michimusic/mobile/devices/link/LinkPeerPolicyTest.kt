package org.michimusic.mobile.devices.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPeerPolicyTest {
    @Test fun manualEndpointRejectsMalformedInput() {
        assertEquals(ParsedLinkEndpoint("micro.local", 8400), parseManualEndpoint("  MICRO.local "))
        assertEquals(ParsedLinkEndpoint("192.168.0.20", 53318), parseManualEndpoint("192.168.0.20:53318"))
        assertNull(parseManualEndpoint("192.168.2.999"))
        assertNull(parseManualEndpoint("https://host.local"))
        assertNull(parseManualEndpoint("fe80::1"))
        assertNull(parseManualEndpoint("node.local:0"))
    }

    @Test fun endpointHintDoesNotOverrideDistinctIdentity() {
        assertTrue(sameDiscoveredEndpoint("MICRO.local", 8400, "micro.LOCAL", 8400))
        assertFalse(sameDiscoveredEndpoint("micro.local", 8400, "micro.local", 53318))
        assertFalse(matchesObservedPeer("michi-a", "10.0.0.2", 8400, "michi-b", "10.0.0.2", 8400))
        assertTrue(matchesObservedPeer("michi-a", "10.0.0.2", 8400, "michi-a", "10.0.0.3", 8400))
    }

    @Test fun streamTypeHasNoImplicitFileTransferCapability() {
        assertTrue(isProvisionedMichiStream("michi-stream-standard"))
        assertTrue(isProvisionedMichiStream("michi_music_stream"))
        assertFalse(isProvisionedMichiStream("desktop"))
        assertTrue(refusesPhysicalButtonForMichiStream("stream", "", "RECEIVER_BUTTON"))
        assertFalse(refusesPhysicalButtonForMichiStream("desktop", "", "RECEIVER_BUTTON"))
    }
}
