package org.michimusic.link

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.identity.MichiIdentity

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var connectionManager: ConnectionManager
    private lateinit var registry: PairedDeviceRegistry
    private lateinit var identity: MichiIdentity

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        registry = mockk(relaxed = true)
        identity = mockk(relaxed = true)
        every { identity.michiId } returns "test_client_id"
        connectionManager = ConnectionManager(registry, identity)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `getClient returns null if not connected`() {
        val client = connectionManager.getClient("unknown_device")
        assertNull(client)
    }

    @Test
    fun `connect sets OFFLINE if device has no URL`() = runTest(testDispatcher) {
        val device = PairedDevice(deviceId = "dev_1", deviceToken = "token", lastUrl = "")
        every { registry.getDevice("dev_1") } returns device

        connectionManager.connect("dev_1")
        
        val state = connectionManager.connectionStates.value["dev_1"]
        assertEquals(SyncConnectionState.OFFLINE, state)
        assertNull(connectionManager.getClient("dev_1"))
    }

    @Test
    fun `updateState properly modifies connection states flow`() = runTest(testDispatcher) {
        connectionManager.updateState("dev_2", SyncConnectionState.CONNECTING)
        
        val states = connectionManager.connectionStates.first()
        assertEquals(SyncConnectionState.CONNECTING, states["dev_2"])
    }

    @Test
    fun `disconnect removes client and updates state`() = runTest(testDispatcher) {
        // Manually create an entry by connecting a mock device
        val device = PairedDevice(deviceId = "dev_3", deviceToken = "token", lastUrl = "http://192.168.1.10")
        every { registry.getDevice("dev_3") } returns device
        
        connectionManager.connect("dev_3")
        assertNotNull(connectionManager.getClient("dev_3"))
        
        connectionManager.disconnect("dev_3")
        
        assertNull(connectionManager.getClient("dev_3"))
        assertEquals(SyncConnectionState.DISCONNECTED, connectionManager.connectionStates.value["dev_3"])
    }

    @Test
    fun `disconnectAll clears all clients`() = runTest(testDispatcher) {
        val device = PairedDevice(deviceId = "dev_4", deviceToken = "token", lastUrl = "http://192.168.1.11")
        every { registry.getDevice("dev_4") } returns device
        
        connectionManager.connect("dev_4")
        assertNotNull(connectionManager.getClient("dev_4"))
        
        connectionManager.disconnectAll()
        
        assertNull(connectionManager.getClient("dev_4"))
        assertEquals(0, connectionManager.connectionStates.value.size)
    }
}
