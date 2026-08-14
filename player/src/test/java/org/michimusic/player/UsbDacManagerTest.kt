package org.michimusic.player

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsbDacManagerTest {

    private lateinit var mockContext: Context

    @Before
    fun setup() {
        mockContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun initialState_hasFallbackSpeakerWhenNoManager() {
        val manager = UsbDacManager(mockContext)
        val devices = manager.outputDevices.value

        assertEquals(1, devices.size)
        assertEquals("Altavoz del Dispositivo", devices[0].name)
        assertTrue(devices[0].isSpeaker)
        assertTrue(devices[0].isSelected)
        assertFalse(manager.dacState.value.isConnected)
    }

    @Test
    fun selectDevice_updatesSelectedDeviceId() {
        val manager = UsbDacManager(mockContext)
        manager.selectDevice(42)

        assertEquals(42, manager.selectedDeviceId.value)
    }

    @Test
    fun clearDeviceSelection_resetsToNull() {
        val manager = UsbDacManager(mockContext)
        manager.selectDevice(10)
        assertEquals(10, manager.selectedDeviceId.value)

        manager.clearDeviceSelection()
        assertEquals(null, manager.selectedDeviceId.value)
    }

    @Test
    fun release_doesNotThrow() {
        val manager = UsbDacManager(mockContext)
        manager.release()
        assertNotNull(manager)
    }
}
