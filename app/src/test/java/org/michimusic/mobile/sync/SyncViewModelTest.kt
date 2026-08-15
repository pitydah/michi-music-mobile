package org.michimusic.mobile.sync

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.identity.MichiIdentity
import org.michimusic.mobile.rules.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private lateinit var viewModel: SyncViewModel
    private lateinit var context: Context
    private lateinit var linkDiscovery: LinkDiscovery
    private lateinit var registry: PairedDeviceRegistry
    private lateinit var connectionManager: ConnectionManager
    private lateinit var identity: MichiIdentity
    private lateinit var trackRepository: SyncedTrackRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        linkDiscovery = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        identity = mockk(relaxed = true)
        trackRepository = mockk(relaxed = true)

        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        every { connectionManager.connectionStates } returns MutableStateFlow(emptyMap())
        every { registry.getAllDevices() } returns emptyList()

        viewModel = SyncViewModel(
            context = context,
            linkDiscovery = linkDiscovery,
            registry = registry,
            connectionManager = connectionManager,
            identity = identity,
            trackRepository = trackRepository
        )
    }

    @Test
    fun `startDiscovery updates connection state to DISCOVERING`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        viewModel.startDiscovery()
        runCurrent()
        
        assertEquals(SyncConnectionState.DISCOVERING, viewModel.uiState.value.state)
        io.mockk.coVerify { linkDiscovery.start() }
    }

    @Test
    fun `stopDiscovery resets connection state`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        viewModel.startDiscovery()
        runCurrent()
        
        viewModel.stopDiscovery()
        runCurrent()
        
        assertEquals(SyncConnectionState.DISCONNECTED, viewModel.uiState.value.state)
        io.mockk.coVerify { linkDiscovery.stop() }
    }

    @Test
    fun `connectToDevice uses connection manager`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        viewModel.connectToDevice("dev_1")
        runCurrent()
        
        io.mockk.verify { connectionManager.connect("dev_1") }
    }

    @Test
    fun `forgetDevice removes from registry and disconnects`() = runTest(testDispatcher) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        viewModel.forgetDevice("dev_1")
        runCurrent()
        
        io.mockk.verify { registry.removeDevice("dev_1") }
        io.mockk.verify { connectionManager.disconnect("dev_1") }
    }
}
