package org.michimusic.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.AnnounceMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalCoroutinesApi::class)
class LinkDiscoveryTest {

    private val testDispatcher = StandardTestDispatcher()
    
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signed canonical Micro announce is verified but never automatically paired`() = runTest {
        val signed = Json.decodeFromString<AnnounceMessage>("""{
            "device_id":"vector-micro-01", "name":"Michi Micro Server",
            "service":"michi-micro-server", "roles":["music_server","library_host","playback_host"],
            "api_version":"v1", "host":"192.168.1.101", "port":8500,
            "features":{"downloads":true,"events":false,"import":true,"library":true,
              "playback":true,"queue":true,"streaming":true,"sync":true},
            "michi_id":"KbOl4D8NYJG5oWEni1BT9VnCDSoMmrXejuwKG1zqo6k",
            "public_key":"gcHspAqCJtSSQX34ikRitVBiRl9Z_7vv2FrRDP1p8GQ",
            "signature":"M8qqQYlam8JvqfAs_aFSep80mzr0aHS9rgQyPnMlMMg4MfkmMdLxxznwu39Q6sN3aufp25QFCBgiC8eap8kqAA",
            "timestamp_ms":1767225601000,
            "nonce":"AgkQFx4lLDM6QUhPVl1kaw"} """)
        val discovery = LinkDiscovery()
        discovery.processAnnounce(signed, "192.168.1.101", now = 1767225601000L)
        val peer = discovery.peers.value["vector-micro-01"]
        assertEquals("VERIFIED", peer?.identityTrust)
        assertEquals("Michi Micro Server", peer?.alias)
        assertEquals("michi-micro-server", peer?.service)
        // Invalid signature cannot replace a known signed address or identity.
        discovery.processAnnounce(signed.copy(signature = signed.signature.dropLast(1) + "x"),
            "192.168.1.101", now = 1767225601001L)
        assertEquals("VERIFIED", discovery.peers.value["vector-micro-01"]?.identityTrust)
        discovery.processAnnounce(signed.copy(signature = "", publicKey = "", michiId = "",
            nonce = "", timestampMs = null), "192.168.1.101", now = 1767225601002L)
        assertEquals("VERIFIED", discovery.peers.value["vector-micro-01"]?.identityTrust)
    }

    @Test
    fun `unsigned Stream and signed group with missing members cannot populate roster`() = runTest {
        val discovery = LinkDiscovery()
        discovery.processAnnounce(AnnounceMessage(deviceId = "fake", service = "michi-stream-hifi",
            name = "Stream", port = 8400), "192.168.1.50", now = 100L)
        assertTrue(discovery.peers.value.isEmpty())
        discovery.processAnnounce(AnnounceMessage(deviceId = "partial", name = "Partial",
            michiId = "michi_fake", port = 8400), "192.168.1.51", now = 100L)
        assertTrue(discovery.peers.value.isEmpty())
    }

    @Test
    fun `multiple devices with identical aliases do not overwrite each other`() = runTest {
        val discovery = LinkDiscovery()
        val msg1 = AnnounceMessage(
            alias = "Michi Node",
            port = 8400,
            device = "player",
            deviceId = "dev_1",
            version = "1.0",
            authRequired = false,
        )
        val msg2 = AnnounceMessage(
            alias = "Michi Node",
            port = 8400,
            device = "player",
            deviceId = "dev_2",
            version = "1.0",
            authRequired = false,
        )

        discovery.processAnnounce(msg1, "192.168.1.100")
        discovery.processAnnounce(msg2, "192.168.1.101")

        val peers = discovery.peers.value
        assertEquals(2, peers.size)
        assertTrue(peers.containsKey("dev_1"))
        assertTrue(peers.containsKey("dev_2"))
        assertEquals("192.168.1.100", peers["dev_1"]?.ip)
        assertEquals("192.168.1.101", peers["dev_2"]?.ip)
    }

    @Test
    fun `spoofed goodbye from different host cannot evict an existing device`() = runTest {
        val discovery = LinkDiscovery()
        val original = AnnounceMessage(alias = "Node", port = 8400, device = "player",
            deviceId = "trusted", version = "1.0", authRequired = false)
        discovery.processAnnounce(original, "192.168.1.20")
        discovery.processAnnounce(original.copy(type = "goodbye"), "192.168.1.99")
        assertEquals("192.168.1.20", discovery.peers.value["trusted"]?.ip)
    }

    @Test
    fun `unsigned announce cannot replace active peer address from another host`() = runTest {
        val discovery = LinkDiscovery()
        val msg = AnnounceMessage(alias = "Trusted", port = 8400, device = "player",
            deviceId = "trusted", version = "1.0", authRequired = true)
        discovery.processAnnounce(msg, "192.168.1.20", now = 100_000L)
        discovery.processAnnounce(msg.copy(alias = "Attacker"), "192.168.1.99", now = 110_000L)
        assertEquals("192.168.1.20", discovery.peers.value["trusted"]?.ip)
        assertEquals("Trusted", discovery.peers.value["trusted"]?.alias)

        // Once the old entry has expired, reconnection from a new address is allowed.
        discovery.cleanStalePeers(now = 190_001L)
        discovery.processAnnounce(msg, "192.168.1.99", now = 190_001L)
        assertEquals("192.168.1.99", discovery.peers.value["trusted"]?.ip)
    }

    @Test
    fun `alias change on same device updates existing entry without duplicate`() = runTest {
        val discovery = LinkDiscovery()
        val initial = AnnounceMessage(
            alias = "Living Room",
            port = 8400,
            device = "player",
            deviceId = "dev_lr",
            version = "1.0",
            authRequired = false,
        )
        val renamed = AnnounceMessage(
            alias = "Studio",
            port = 8400,
            device = "player",
            deviceId = "dev_lr",
            version = "1.0",
            authRequired = false,
        )

        discovery.processAnnounce(initial, "192.168.1.50")
        assertEquals("Living Room", discovery.peers.value["dev_lr"]?.alias)

        discovery.processAnnounce(renamed, "192.168.1.50")
        assertEquals(1, discovery.peers.value.size)
        assertEquals("Studio", discovery.peers.value["dev_lr"]?.alias)
    }

    @Test
    fun `stale peers expire after timeout threshold`() = runTest {
        val discovery = LinkDiscovery()
        val msg = AnnounceMessage(
            alias = "Temporary Node",
            port = 8400,
            device = "player",
            deviceId = "dev_temp",
            version = "1.0",
            authRequired = false,
        )

        val t0 = 100_000L
        discovery.processAnnounce(msg, "192.168.1.200", now = t0)
        assertEquals(1, discovery.peers.value.size)

        // Link announces every 30s; 60s must not cause a false offline state.
        discovery.cleanStalePeers(now = t0 + 60_000L)
        assertEquals(1, discovery.peers.value.size)

        // At t0 + 91s: expired (> 90_000L PEER_TIMEOUT_MS)
        discovery.cleanStalePeers(now = t0 + 91_000L)
        assertTrue(discovery.peers.value.isEmpty())
    }

    @Test
    fun `manual stop retains observed peers until a later full reset`() = runTest {
        val discovery = LinkDiscovery()
        val msg = AnnounceMessage(alias = "Studio", port = 8400, device = "player",
            deviceId = "studio", version = "1.0", authRequired = true)
        discovery.processAnnounce(msg, "192.168.1.21")
        discovery.stop(preservePeers = true)
        assertTrue(discovery.peers.value.containsKey("studio"))
        discovery.stop()
        assertTrue(discovery.peers.value.isEmpty())
    }

    @Test
    fun `goodbye message immediately removes peer and emits PeerLost event`() = runTest {
        val discovery = LinkDiscovery()
        val msg = AnnounceMessage(
            alias = "Node",
            port = 8400,
            device = "player",
            deviceId = "dev_node",
            version = "1.0",
            authRequired = false,
        )
        val goodbye = AnnounceMessage(
            alias = "Node",
            port = 8400,
            device = "player",
            deviceId = "dev_node",
            version = "1.0",
            authRequired = false,
            type = "goodbye",
        )

        discovery.processAnnounce(msg, "192.168.1.20")
        assertEquals(1, discovery.peers.value.size)

        val lostEvents = mutableListOf<DiscoveryEvent.PeerLost>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            discovery.events.collect { event ->
                if (event is DiscoveryEvent.PeerLost) lostEvents.add(event)
            }
        }

        discovery.processAnnounce(goodbye, "192.168.1.20")

        assertTrue(discovery.peers.value.isEmpty())
        assertEquals(1, lostEvents.size)
        assertEquals("dev_node", lostEvents[0].peerKey)
        assertEquals("Node", lostEvents[0].alias)
        job.cancel()
    }

    @Test
    fun `cleanStalePeers emits PeerLost event with key and alias`() = runTest {
        val discovery = LinkDiscovery()
        val msg = AnnounceMessage(
            alias = "Living Room",
            port = 8400,
            device = "player",
            deviceId = "dev_lr",
            version = "1.0",
            authRequired = false,
        )
        val t0 = 100_000L
        discovery.processAnnounce(msg, "192.168.1.20", now = t0)

        val lostEvents = mutableListOf<DiscoveryEvent.PeerLost>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            discovery.events.collect { event ->
                if (event is DiscoveryEvent.PeerLost) lostEvents.add(event)
            }
        }

        discovery.cleanStalePeers(now = t0 + 91_000L)

        assertTrue(discovery.peers.value.isEmpty())
        assertEquals(1, lostEvents.size)
        assertEquals("dev_lr", lostEvents[0].peerKey)
        assertEquals("Living Room", lostEvents[0].alias)
        job.cancel()
    }
}
