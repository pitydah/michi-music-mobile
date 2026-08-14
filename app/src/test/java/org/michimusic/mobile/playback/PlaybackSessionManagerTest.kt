package org.michimusic.mobile.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.LinkSession

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var linkSession: LinkSession
    private lateinit var linkDiscovery: LinkDiscovery
    private lateinit var sessionManager: PlaybackSessionManager

    @Before
    fun setup() {
        linkSession = LinkSession()
        linkDiscovery = LinkDiscovery(context = null)
        sessionManager = PlaybackSessionManager(
            audioController = null,
            linkSession = linkSession,
            linkDiscovery = linkDiscovery,
            scope = testScope,
        )
    }

    @Test
    fun initialEndpoint_isLocalPhone() {
        val state = sessionManager.sessionState.value
        assertEquals(PlaybackEndpoint.LocalPhone.id, state.activeEndpoint.id)
        assertTrue(state.activeEndpoint.isLocal)
        assertFalse(state.isRemoteActive)
    }

    @Test
    fun availableEndpoints_includesLocalPhoneByDefault() {
        val state = sessionManager.sessionState.value
        assertTrue(state.availableEndpoints.any { it.isLocal && it.name == "Este teléfono" })
    }

    @Test
    fun selectLocalEndpoint_resetsToLocalPhone() {
        sessionManager.selectLocalEndpoint()
        val state = sessionManager.sessionState.value
        assertEquals(PlaybackEndpoint.LocalPhone.id, state.activeEndpoint.id)
        assertFalse(state.isRemoteActive)
    }

    @Test
    fun switchEndpoint_toLocalPhone_executesCallback() = runTest(testDispatcher) {
        var callbackCalled = false
        sessionManager.switchEndpoint(PlaybackEndpoint.LocalPhone) { success, msg ->
            callbackCalled = true
            assertTrue(success)
            assertTrue(msg.contains("teléfono"))
        }

        assertTrue(callbackCalled)
        assertEquals(PlaybackEndpoint.LocalPhone.id, sessionManager.sessionState.value.activeEndpoint.id)
    }
}
