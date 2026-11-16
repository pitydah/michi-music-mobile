package org.michimusic.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkAnnounceSourceBindingPolicyTest {
    @Test fun numericIpv4HandoffFromSameSenderAllowed() =
        assertTrue(LinkAnnounceSourceBindingPolicy.mayMove("192.168.1.8", "192.168.1.8"))

    @Test fun validSignedHostnameDoesNotAuthorizeSourceHandoff() =
        assertFalse(LinkAnnounceSourceBindingPolicy.mayMove("michi.local", "192.168.1.8"))

    @Test fun differentNumericSourceIsRejected() =
        assertFalse(LinkAnnounceSourceBindingPolicy.mayMove("192.168.1.8", "192.168.1.9"))

    @Test fun malformedOrDnsSourceRejectedWithoutResolving() {
        assertFalse(LinkAnnounceSourceBindingPolicy.mayMove("192.168.1.8", "spoof.local"))
        assertFalse(LinkAnnounceSourceBindingPolicy.mayMove("999.168.1.8", "999.168.1.8"))
    }

    @Test fun ipv6LiteralWithoutZoneMayMigrate() =
        assertTrue(LinkAnnounceSourceBindingPolicy.mayMove("fe80::1", "fe80:0:0:0:0:0:0:1"))
}
