package org.michimusic.link

import io.ktor.client.engine.mock.respond
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
        val device = PairedDevice(deviceId = "dev_3", deviceToken = "token", lastUrl = "http://192.168.1.10")
        every { registry.getDevice("dev_3") } returns device
        
        connectionManager.connect("dev_3")
        assertNotNull(connectionManager.getClient("dev_3"))
        
        connectionManager.disconnect("dev_3")
        
        assertNull(connectionManager.getClient("dev_3"))
        assertEquals(SyncConnectionState.DISCONNECTED, connectionManager.connectionStates.value["dev_3"])
    }

    @Test
    fun `disconnectDevice behaves identically to disconnect`() = runTest(testDispatcher) {
        val device = PairedDevice(deviceId = "dev_5", deviceToken = "token", lastUrl = "http://192.168.1.12")
        every { registry.getDevice("dev_5") } returns device
        
        connectionManager.connect("dev_5")
        assertNotNull(connectionManager.getClient("dev_5"))
        
        connectionManager.disconnectDevice("dev_5")
        
        assertNull(connectionManager.getClient("dev_5"))
        assertEquals(SyncConnectionState.DISCONNECTED, connectionManager.connectionStates.value["dev_5"])
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

    @Test
    fun `identity pinning rejects reconnection on Michi ID mismatch`() = runTest(testDispatcher) {
        val mockEngine = io.ktor.client.engine.mock.MockEngine {
            respond(
                content = """{"server_id":"srv_id_1","michi_id":"srv_michi_DIFFERENT","public_key":"pk_original","service":"music_server"}""",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, io.ktor.http.ContentType.Application.Json.toString())
            )
        }
        val mockClient = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            deviceToken = "token",
            sessionToken = "token",
            clientDeviceId = "test_client_id",
            httpClient = io.ktor.client.HttpClient(mockEngine) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                }
            }
        )

        val device = PairedDevice(
            deviceId = "dev_pinned",
            deviceToken = "token",
            lastUrl = "http://127.0.0.1:7331",
            michiId = "srv_michi_1",
            publicKey = "pk_original",
            serverId = "srv_id_1"
        )
        every { registry.getDevice("dev_pinned") } returns device

        val cm = object : ConnectionManager(registry, identity) {
            init {
                clients["dev_pinned"] = mockClient
            }
        }

        cm.connect("dev_pinned")

        var attempts = 0
        while (attempts < 50 && cm.connectionStates.value["dev_pinned"] != SyncConnectionState.UNAUTHORIZED) {
            Thread.sleep(50)
            attempts++
        }

        assertEquals(SyncConnectionState.UNAUTHORIZED, cm.connectionStates.value["dev_pinned"])
    }

    @Test
    fun `identity pinning rejects reconnection on Server ID mismatch`() = runTest(testDispatcher) {
        val mockEngine = io.ktor.client.engine.mock.MockEngine {
            respond(
                content = """{"server_id":"srv_id_DIFFERENT","michi_id":"srv_michi_1","public_key":"pk_original","service":"music_server"}""",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, io.ktor.http.ContentType.Application.Json.toString())
            )
        }
        val mockClient = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            deviceToken = "token",
            sessionToken = "token",
            clientDeviceId = "test_client_id",
            httpClient = io.ktor.client.HttpClient(mockEngine) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                }
            }
        )

        val device = PairedDevice(
            deviceId = "dev_pinned_server",
            deviceToken = "token",
            lastUrl = "http://127.0.0.1:7331",
            michiId = "srv_michi_1",
            publicKey = "pk_original",
            serverId = "srv_id_1"
        )
        every { registry.getDevice("dev_pinned_server") } returns device

        val cm = object : ConnectionManager(registry, identity) {
            init {
                clients["dev_pinned_server"] = mockClient
            }
        }

        cm.connect("dev_pinned_server")

        var attempts = 0
        while (attempts < 50 && cm.connectionStates.value["dev_pinned_server"] != SyncConnectionState.UNAUTHORIZED) {
            Thread.sleep(50)
            attempts++
        }

        assertEquals(SyncConnectionState.UNAUTHORIZED, cm.connectionStates.value["dev_pinned_server"])
    }

    @Test
    fun `device A and device B maintain isolated clients and credentials`() = runTest(testDispatcher) {
        val devA = PairedDevice(
            deviceId = "dev_A",
            deviceToken = "token_A",
            pairedClientDeviceId = "client_A",
            lastUrl = "http://192.168.1.100:5252"
        )
        val devB = PairedDevice(
            deviceId = "dev_B",
            deviceToken = "token_B",
            pairedClientDeviceId = "client_B",
            lastUrl = "http://192.168.1.200:5252"
        )
        every { registry.getDevice("dev_A") } returns devA
        every { registry.getDevice("dev_B") } returns devB

        connectionManager.connect("dev_A")
        connectionManager.connect("dev_B")

        val clientA = connectionManager.getClient("dev_A")
        val clientB = connectionManager.getClient("dev_B")

        assertNotNull(clientA)
        assertNotNull(clientB)
        assertNotSame(clientA, clientB)
        assertEquals("token_A", clientA?.deviceToken)
        assertEquals("token_B", clientB?.deviceToken)
        assertEquals("client_A", clientA?.pairedClientDeviceId)
        assertEquals("client_B", clientB?.pairedClientDeviceId)
    }
}
