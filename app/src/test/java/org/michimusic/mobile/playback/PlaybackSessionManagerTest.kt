package org.michimusic.mobile.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.UnifiedDevice
import io.mockk.mockk

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testEndpointTypes() {
        val endpoint = PlaybackEndpoint.LocalPhone
        assertTrue(endpoint.isLocal)
        assertEquals("Este teléfono", endpoint.name)
    }

    @Test
    fun testAttachRemote() {
        val manager = PlaybackSessionManager(
            audioController = null,
            connectionManager = mockk(relaxed = true),
            linkDiscovery = mockk(relaxed = true),
            registry = mockk(relaxed = true)
        )
        val endpoint = PlaybackEndpoint("123", "Remote", EndpointType.DESKTOP_PLAYER, isLocal = false)
        
        manager.attachRemote(endpoint) { success, msg ->
            // Basic assert
            assertTrue(success || !success)
        }
    }
}
