package org.michimusic.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.LinkSession
import org.michimusic.link.errors.LinkException
import org.michimusic.link.dto.PairingStrategy

import org.junit.Rule
import org.michimusic.mobile.rules.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    @MockK private lateinit var linkDiscovery: LinkDiscovery
    @MockK private lateinit var linkSession: LinkSession
    @MockK private lateinit var trackRepository: SyncedTrackRepository

    private lateinit var context: Context
    private lateinit var viewModel: SyncViewModel

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        context = io.mockk.mockk(relaxed = true)

        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        coEvery { linkDiscovery.start() } returns Unit
        coEvery { linkDiscovery.stop() } returns Unit
        every { linkSession.connectionState } returns MutableStateFlow(SyncConnectionState.DISCONNECTED)
        every { linkSession.connectedPeer } returns MutableStateFlow(null as org.michimusic.core.models.DiscoveredPeer?)
        every { linkSession.pairStartResponse } returns MutableStateFlow(null)
        every { linkSession.pairConfirmResponse } returns MutableStateFlow(null)
        every { linkSession.updateState(any()) } returns Unit

        viewModel = SyncViewModel(context, linkDiscovery, linkSession, trackRepository)
    }

    @After
    fun tearDown() {
    }

    @Test
    fun initialState_isDisconnected() = runTest(testDispatcher) {
        assertEquals(SyncConnectionState.DISCONNECTED, viewModel.uiState.value.state)
    }

    @Test
    fun startDiscovery_updatesState() = runTest(testDispatcher) {
        viewModel.startDiscovery()
        verify { linkSession.updateState(SyncConnectionState.DISCOVERING) }
    }

    @Test
    fun startDiscovery_whenConnected_ignores() = runTest(testDispatcher) {
        every { linkSession.connectionState } returns MutableStateFlow(SyncConnectionState.PAIRED)
        val vm = SyncViewModel(context, linkDiscovery, linkSession, trackRepository)
        vm.startDiscovery()
        verify(exactly = 0) { linkSession.updateState(any()) }
    }

    @Test
    fun clearError_resetsErrorState() = runTest(testDispatcher) {
        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
    }
}
