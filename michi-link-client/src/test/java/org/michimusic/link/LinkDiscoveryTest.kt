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

        // At t0 + 10s: not expired
        discovery.cleanStalePeers(now = t0 + 10_000L)
        assertEquals(1, discovery.peers.value.size)

        // At t0 + 16s: expired (> 15_000L PEER_TIMEOUT_MS)
        discovery.cleanStalePeers(now = t0 + 16_000L)
        assertTrue(discovery.peers.value.isEmpty())
    }

    @Test
    fun `goodbye message immediately removes peer`() = runTest {
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

        discovery.processAnnounce(goodbye, "192.168.1.20")
        assertTrue(discovery.peers.value.isEmpty())
    }
}
