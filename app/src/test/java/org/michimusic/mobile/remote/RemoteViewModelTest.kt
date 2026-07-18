package org.michimusic.mobile.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkSession
import org.michimusic.link.dto.PlaybackStateDto

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var session: LinkSession
    private lateinit var viewModel: RemoteViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        session = LinkSession()
        val json = Json { ignoreUnknownKeys = true }
        val engine = MockEngine { request ->
            respond(
                content = """{"state":"stopped","playing":false}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://192.168.1.100:53318",
            deviceToken = "test-token",
            clientDeviceId = "test-phone",
            httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(json) }
            },
        )
        session.onConnected(DiscoveredPeer(ip = "192.168.1.100", port = 53318, alias = "Test Server"), client)
        viewModel = RemoteViewModel(session)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        viewModel.disconnect()
    }

    @Test
    fun initialState_isDisconnected() {
        val state = viewModel.uiState.value
        assertEquals(RemoteConnectionState.DISCONNECTED, state.connState)
        assertFalse(state.connected)
        assertEquals(RemoteSourceMode.LOCAL, state.mode)
    }

    @Test
    fun connect_setsConnectedState() {
        viewModel.connectIfNeeded()
        val state = viewModel.uiState.value
        assertTrue(state.connected)
        assertEquals(RemoteConnectionState.CONNECTED, state.connState)
        assertEquals(RemoteSourceMode.REMOTE, state.mode)
    }

    @Test
    fun disconnect_resetsToInitial() {
        viewModel.connectIfNeeded()
        viewModel.disconnect()
        val state = viewModel.uiState.value
        assertFalse(state.connected)
        assertEquals(RemoteConnectionState.DISCONNECTED, state.connState)
    }

    @Test
    fun setVolume_updatesLocally() = runTest(testDispatcher) {
        viewModel.connectIfNeeded()
        advanceUntilIdle()
        viewModel.setVolume(75)
        advanceUntilIdle()
        assertEquals(75, viewModel.uiState.value.playerState.volume)
    }

    @Test
    fun setVolume_clampsTo100() = runTest(testDispatcher) {
        viewModel.connectIfNeeded()
        advanceUntilIdle()
        viewModel.setVolume(150)
        advanceUntilIdle()
        assertEquals(100, viewModel.uiState.value.playerState.volume)
    }

    @Test
    fun queueJump_callsClient() = runTest(testDispatcher) {
        viewModel.connectIfNeeded()
        advanceUntilIdle()
        viewModel.queueJump(3)
        advanceUntilIdle()
    }
}
