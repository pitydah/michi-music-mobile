package org.michimusic.player

import android.content.Intent
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.player.PlayerDependencies
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MichiPlaybackServiceTest {

    @Before
    fun setup() {
        mockkObject(PlayerDependencies)
        PlayerDependencies.replayGainDao = io.mockk.mockk<ReplayGainDao>(relaxed = true)
        val mockEffects = io.mockk.mockk<MichiAudioEffects>(relaxed = true)
        io.mockk.every { mockEffects.effectsState } returns kotlinx.coroutines.flow.MutableStateFlow(AudioEffectsState())
        val mockUsb = io.mockk.mockk<UsbDacManager>(relaxed = true)
        io.mockk.every { mockUsb.routeStatus } returns kotlinx.coroutines.flow.MutableStateFlow(AudioRouteStatus.NoDevice)
        PlayerDependencies.usbDacManager = mockUsb
    }

    @After
    fun teardown() {
        PlayerDependencies.audioEffects = null
        PlayerDependencies.replayGainDao = null
        PlayerDependencies.usbDacManager = null
        PlayerDependencies.signalTruthTracker = null
        PlayerDependencies.audioModeManager = null
        PlayerDependencies.rtpPcmAudioTap = null
        unmockkAll()
    }

    @Test
    fun `onCreate initializes service successfully`() {
        val controller = Robolectric.buildService(MichiPlaybackService::class.java)
        controller.create()
        
        val service = controller.get()
        assertNotNull(service)
        
        controller.destroy()
    }

    @Test
    fun `onTaskRemoved handles task removal`() {
        val controller = Robolectric.buildService(MichiPlaybackService::class.java)
        controller.create()
        
        val service = controller.get()
        service.onTaskRemoved(Intent())
        
        // No crash should happen, it should defer save and possibly stop
        controller.destroy()
    }
}
