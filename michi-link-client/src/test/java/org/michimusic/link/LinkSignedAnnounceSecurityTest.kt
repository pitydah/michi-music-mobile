package org.michimusic.link

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.core.models.AnnounceMessage

/** Golden data from michi-link/tests/vectors/generated at the pinned contract HEAD. */
class LinkSignedAnnounceSecurityTest {
    private val micro = AnnounceMessage(
        deviceId = "vector-micro-01", name = "Michi Micro Server", service = "michi-micro-server",
        roles = listOf("music_server", "library_host", "playback_host"), apiVersion = "v1",
        host = "192.168.1.101", port = 8500,
        features = mapOf("downloads" to true, "events" to false, "import" to true, "library" to true,
            "playback" to true, "queue" to true, "streaming" to true, "sync" to true),
        michiId = "KbOl4D8NYJG5oWEni1BT9VnCDSoMmrXejuwKG1zqo6k",
        publicKey = "gcHspAqCJtSSQX34ikRitVBiRl9Z_7vv2FrRDP1p8GQ",
        signature = "M8qqQYlam8JvqfAs_aFSep80mzr0aHS9rgQyPnMlMMg4MfkmMdLxxznwu39Q6sN3aufp25QFCBgiC8eap8kqAA",
        timestampMs = 1767225601000L, nonce = "AgkQFx4lLDM6QUhPVl1kaw",
    )
    private val stream = micro.copy(
        deviceId = "vector-stream-01", name = "Michi Stream Hi-Fi", service = "michi-stream-hifi",
        roles = listOf("audio_receiver"), apiVersion = "v1-lite", host = "192.168.1.102",
        port = 8600, features = mapOf("heartbeat" to true, "session" to true, "volume" to true),
        signature = "KDPp20E2PknbZHrRNL5onR1tb5bJItSfxcNKhOT-eMYXcT6qYN2wNDcwgGxiZFNrXFT16HWKRXSVgV7dO-TuAQ",
        timestampMs = 1767225602000L, nonce = "AwoRGB8mLTQ7QklQV15lbA",
    )

    @Test fun signedMicroRustVectorPasses() = assertEquals(
        LinkSignedAnnounceSecurity.Level.VERIFIED,
        LinkSignedAnnounceSecurity().verify(micro, micro.host, micro.timestampMs!!),
    )

    @Test fun signedStreamRustVectorPasses() = assertEquals(
        LinkSignedAnnounceSecurity.Level.VERIFIED,
        LinkSignedAnnounceSecurity().verify(stream, stream.host, stream.timestampMs!!),
    )

    @Test fun tamperingAnyFunctionalFieldFails() {
        for (tampered in listOf(
            micro.copy(name = "Mallory"), micro.copy(host = "192.168.1.99"),
            micro.copy(features = micro.features + ("sync" to false)),
            micro.copy(port = 443), micro.copy(roles = listOf("music_server")),
            micro.copy(michiId = stream.publicKey), micro.copy(publicKey = micro.michiId),
            micro.copy(type = "goodbye"),
        )) assertEquals(LinkSignedAnnounceSecurity.Level.INVALID,
            LinkSignedAnnounceSecurity().verify(tampered, "192.168.1.101", 1767225601000L))
    }

    @Test fun replayAndExpirationFail() {
        val security = LinkSignedAnnounceSecurity()
        assertEquals(LinkSignedAnnounceSecurity.Level.VERIFIED, security.verify(micro, micro.host, 1767225601000L))
        assertEquals(LinkSignedAnnounceSecurity.Level.INVALID, security.verify(micro, micro.host, 1767225601000L))
        assertEquals(LinkSignedAnnounceSecurity.Level.INVALID,
            LinkSignedAnnounceSecurity().verify(micro, micro.host, 1767225601000L + 90_001L))
    }

    @Test fun hostileSourceAndPartialGroupFail() {
        assertEquals(LinkSignedAnnounceSecurity.Level.INVALID,
            LinkSignedAnnounceSecurity().verify(micro, "192.168.1.105", 1767225601000L))
        assertEquals(LinkSignedAnnounceSecurity.Level.INVALID,
            LinkSignedAnnounceSecurity().verify(micro.copy(signature = ""), micro.host, 1767225601000L))
    }

    @Test fun unsignedLegacyNeverBecomesVerified() {
        val legacy = AnnounceMessage(deviceId = "legacy", alias = "old player")
        assertEquals(LinkSignedAnnounceSecurity.Level.UNTRUSTED,
            LinkSignedAnnounceSecurity().verify(legacy, "192.168.1.50", 1767225601000L))
        assertEquals(LinkSignedAnnounceSecurity.Level.INVALID,
            LinkSignedAnnounceSecurity().verify(legacy.copy(service = "michi-stream-hifi"),
                "192.168.1.50", 1767225601000L))
    }
}
