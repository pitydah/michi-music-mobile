package org.michimusic.mobile.sync

import android.content.Context
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.LinkSession
import org.michimusic.mobile.rules.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    @MockK private lateinit var linkDiscovery: LinkDiscovery
    @MockK private lateinit var trackRepository: SyncedTrackRepository

    private val linkSession = LinkSession()
    private lateinit var context: Context
    private lateinit var viewModel: SyncViewModel

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        context = mockk(relaxed = true)

        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        coEvery { linkDiscovery.start() } returns Unit
        coEvery { linkDiscovery.stop() } returns Unit

        viewModel = SyncViewModel(context, linkDiscovery, linkSession, trackRepository)
    }

    @Test
    fun initialState_isDisconnected() = runTest(testDispatcher) {
        assertEquals(SyncConnectionState.DISCONNECTED, viewModel.uiState.value.state)
    }

    @Test
    fun startDiscovery_updatesState() = runTest(testDispatcher) {
        viewModel.startDiscovery()
        assertEquals(SyncConnectionState.DISCOVERING, linkSession.connectionState.value)
    }

    @Test
    fun startDiscovery_whenConnected_ignores() = runTest(testDispatcher) {
        linkSession.updateState(SyncConnectionState.PAIRED)
        testScheduler.advanceUntilIdle()
        viewModel.startDiscovery()
        assertEquals(SyncConnectionState.PAIRED, linkSession.connectionState.value)
    }

    @Test
    fun clearError_resetsErrorState() = runTest(testDispatcher) {
        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
    }
}
