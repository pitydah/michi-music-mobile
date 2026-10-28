package org.michimusic.player

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsbDacManagerTest {

    private lateinit var mockContext: Context

    private class FakeAudioDeviceSource(
        var devices: MutableList<OutputDescriptor> = mutableListOf(),
    ) : AudioDeviceSource {
        private val listeners = mutableListOf<AudioDeviceSource.DeviceChangeListener>()

        override fun getOutputDevices(): List<OutputDescriptor> = devices.toList()

        override fun addListener(listener: AudioDeviceSource.DeviceChangeListener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: AudioDeviceSource.DeviceChangeListener) {
            listeners.remove(listener)
        }

        fun updateDevices(newDevices: List<OutputDescriptor>) {
            devices = newDevices.toMutableList()
            listeners.toList().forEach { it.onDevicesChanged() }
        }

        fun listenerCount(): Int = listeners.size
    }

    private class FakeAudioRouteObserver : AudioRouteObserver {
        private val _routeStatus = kotlinx.coroutines.flow.MutableStateFlow<AudioRouteStatus>(AudioRouteStatus.NoDevice)
        override val routeStatus: kotlinx.coroutines.flow.StateFlow<AudioRouteStatus> = _routeStatus

        var lastRequested: OutputDescriptor? = null
        var requestCount: Int = 0
        var lastDetected: OutputDescriptor? = null
        var lastFailedReason: RouteFailure? = null
        var isReleased = false

        override fun onDeviceRequested(descriptor: OutputDescriptor?) {
            requestCount++
            lastRequested = descriptor
            if (descriptor == null) {
                _routeStatus.value = AudioRouteStatus.NoDevice
            } else {
                _routeStatus.value = AudioRouteStatus.Requested(descriptor)
            }
        }

        override fun onDeviceDetected(descriptor: OutputDescriptor?) {
            lastDetected = descriptor
            if (descriptor != null && _routeStatus.value !is AudioRouteStatus.Requested) {
                _routeStatus.value = AudioRouteStatus.Detected(descriptor)
            }
        }

        override fun onRoutingFailed(deviceId: Int?, failure: RouteFailure) {
            lastFailedReason = failure
            _routeStatus.value = AudioRouteStatus.Failed(deviceId, failure)
        }

        override fun attachAudioTrack(audioTrack: android.media.AudioTrack, generation: Long) {}
        override fun detachAudioTrack(audioTrack: android.media.AudioTrack?, generation: Long?) {}
        override fun release() {
            isReleased = true
            _routeStatus.value = AudioRouteStatus.NoDevice
        }
    }

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
    fun selectDevice_rejectsMissingId() {
        val manager = UsbDacManager(mockContext)
        manager.selectDevice(42)
        assertNull(manager.selectedDeviceId.value)
        assertTrue(manager.routeStatus.value is AudioRouteStatus.Failed)
    }

    @Test
    fun clearDeviceSelection_resetsToNull() {
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, FakeAudioRouteObserver())
        fakeSource.updateDevices(listOf(OutputDescriptor(10, "Test DAC", AudioDeviceInfo.TYPE_USB_DEVICE)))
        manager.selectDevice(10)
        assertEquals(10, manager.selectedDeviceId.value)
        manager.clearDeviceSelection()
        assertNull(manager.selectedDeviceId.value)
    }

    @Test
    fun release_doesNotThrow() {
        val manager = UsbDacManager(mockContext)
        manager.release()
        assertNotNull(manager)
    }

    @Test
    fun usbDetectedNotRoutedTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        val usbDesc = OutputDescriptor(
            androidDeviceId = 101,
            name = "FiiO KA1",
            androidType = AudioDeviceInfo.TYPE_USB_DEVICE,
            announcedSampleRates = listOf(44100, 48000, 96000),
            announcedChannelCounts = listOf(2),
            announcedEncodings = listOf(2),
        )

        fakeSource.updateDevices(listOf(usbDesc))

        // Conectar USB lo detecta y solicita en la máquina de estados, pero NO está confirmado automáticamente
        assertTrue(manager.dacState.value.isConnected)
        assertEquals("FiiO KA1", manager.dacState.value.deviceName)
        assertEquals(101, manager.dacState.value.deviceId)
        assertEquals(listOf(44100, 48000, 96000), manager.dacState.value.sampleRates)

        // Verificamos que no afirma estar confirmado en hardware
        assertFalse(manager.routeStatus.value is AudioRouteStatus.Confirmed)
        assertTrue(manager.routeStatus.value is AudioRouteStatus.Requested)
    }

    @Test
    fun usbDisconnectPausesTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        var disconnectNotified = false
        manager.onUsbDisconnectedListener = {
            disconnectNotified = true
        }

        val usbDesc = OutputDescriptor(
            androidDeviceId = 101,
            name = "AudioQuest Dragonfly",
            androidType = AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        fakeSource.updateDevices(listOf(usbDesc))
        assertTrue(manager.dacState.value.isConnected)
        assertFalse(disconnectNotified)

        // Simular desenchufe de dongle USB
        fakeSource.updateDevices(emptyList())

        assertTrue(disconnectNotified)
        assertFalse(manager.dacState.value.isConnected)
        assertEquals(RouteFailure.DISCONNECTED, observer.lastFailedReason)
        assertTrue(manager.routeStatus.value is AudioRouteStatus.Failed)
    }

    @Test
    fun usbReplugNewIdTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        // Primera conexión
        fakeSource.updateDevices(
            listOf(
                OutputDescriptor(androidDeviceId = 101, name = "Dongle", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
            )
        )
        assertEquals(101, manager.dacState.value.deviceId)

        // Desconexión
        fakeSource.updateDevices(emptyList())
        assertFalse(manager.dacState.value.isConnected)

        // Reconexión: Android asigna un nuevo ID efímero (p. ej. 202)
        fakeSource.updateDevices(
            listOf(
                OutputDescriptor(androidDeviceId = 202, name = "Dongle", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
            )
        )
        assertTrue(manager.dacState.value.isConnected)
        assertEquals(202, manager.dacState.value.deviceId)
        // A different Android ID does not prove this is the original output.
        assertEquals(RouteFailure.DISCONNECTED, observer.lastFailedReason)
        manager.clearDeviceSelection() // user confirms recovery
        assertEquals(202, observer.lastRequested?.androidDeviceId)
    }

    @Test
    fun repeatedUsbScanMustNotIssueDuplicateRoutingRequest() {
        val observer = FakeAudioRouteObserver()
        val source = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, source, observer)
        val dac = OutputDescriptor(101, "DAC estable", AudioDeviceInfo.TYPE_USB_DEVICE)
        source.updateDevices(listOf(dac))
        val requestsAfterAttach = observer.requestCount
        source.updateDevices(listOf(dac))
        source.updateDevices(listOf(dac))
        assertEquals(requestsAfterAttach, observer.requestCount)
        // Changing the actual selected output must issue a new request.
        source.updateDevices(listOf(dac, OutputDescriptor(202, "DAC B", AudioDeviceInfo.TYPE_USB_DEVICE)))
        manager.selectDevice(202)
        assertEquals(202, observer.lastRequested?.androidDeviceId)
        assertTrue(observer.requestCount > requestsAfterAttach)
    }

    @Test
    fun twoDacsChooseRequestedTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        val dac1 = OutputDescriptor(androidDeviceId = 101, name = "DAC Primario", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
        val dac2 = OutputDescriptor(androidDeviceId = 102, name = "DAC Secundario", androidType = AudioDeviceInfo.TYPE_USB_HEADSET)

        fakeSource.updateDevices(listOf(dac1, dac2))
        assertEquals(2, manager.outputDevices.value.size)

        // Por defecto toma el primer USB
        assertEquals(101, observer.lastRequested?.androidDeviceId)

        // Usuario selecciona explícitamente el segundo DAC
        manager.selectDevice(102)
        assertEquals(102, manager.selectedDeviceId.value)
        assertEquals(102, observer.lastRequested?.androidDeviceId)
    }

    @Test
    fun unknownRouteDoesNotClaimConfirmedTest() {
        val defaultObserver = DefaultAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, defaultObserver)

        val dac = OutputDescriptor(androidDeviceId = 55, name = "DAC X", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
        fakeSource.updateDevices(listOf(dac))

        // Al no existir una pista de audio reproduciéndose activamente con listener confirmado,
        // no se puede afirmar Confirmed
        assertFalse(manager.routeStatus.value is AudioRouteStatus.Confirmed)
        assertTrue(manager.routeStatus.value is AudioRouteStatus.Requested)
    }

    @Test
    fun routeCallbackReleasedTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        assertEquals(1, fakeSource.listenerCount())
        manager.release()
        assertEquals(0, fakeSource.listenerCount())
        assertTrue(observer.isReleased)
    }

    @Test
    fun usbHeadsetDifferentiatesFromDacTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        val headset = OutputDescriptor(androidDeviceId = 77, name = "USB-C Earphones", androidType = AudioDeviceInfo.TYPE_USB_HEADSET)
        fakeSource.updateDevices(listOf(headset))

        assertTrue(manager.dacState.value.isConnected)
        assertTrue(manager.dacState.value.isUsbHeadset)
        val dev = manager.outputDevices.value.first()
        assertTrue(dev.isUsbHeadset)
        assertFalse(dev.isUsbDac)

        val dac = OutputDescriptor(androidDeviceId = 88, name = "FiiO KA13", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
        fakeSource.updateDevices(listOf(dac))
        assertTrue(manager.dacState.value.isConnected)
        assertFalse(manager.dacState.value.isUsbHeadset)
        val dacDev = manager.outputDevices.value.first()
        assertTrue(dacDev.isUsbDac)
        assertFalse(dacDev.isUsbHeadset)
    }

    @Test
    fun simultaneousUsbHeadsetAndDacPriorityTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        val dac = OutputDescriptor(androidDeviceId = 101, name = "Chord Mojo 2", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
        val headset = OutputDescriptor(androidDeviceId = 102, name = "Pixel USB-C Earbuds", androidType = AudioDeviceInfo.TYPE_USB_HEADSET)

        fakeSource.updateDevices(listOf(dac, headset))
        assertEquals(2, manager.outputDevices.value.size)
        // First USB device is selected by default
        assertEquals(101, observer.lastRequested?.androidDeviceId)

        // Switch manually to USB headset
        manager.selectDevice(102)
        assertEquals(102, manager.selectedDeviceId.value)
        assertEquals(102, observer.lastRequested?.androidDeviceId)
    }

    @Test
    fun disconnectDuringPlaybackPausesAndEmitsDisconnectedStatusTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        var paused = false
        manager.onUsbDisconnectedListener = { paused = true }

        val dac = OutputDescriptor(androidDeviceId = 50, name = "iFi Zen DAC", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
        fakeSource.updateDevices(listOf(dac))
        assertTrue(manager.dacState.value.isConnected)

        // Disconnect
        fakeSource.updateDevices(emptyList())
        assertFalse(manager.dacState.value.isConnected)
        assertTrue(paused)
        assertTrue(observer.lastFailedReason == RouteFailure.DISCONNECTED)
    }

    @Test
    fun requestedDeviceRoutedElsewhereCannotBeConfirmed() {
        val wanted = OutputDescriptor(7, "USB DAC", AudioDeviceInfo.TYPE_USB_DEVICE)
        val actual = OutputDescriptor(9, "Speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val status = classifyObservedAudioRoute(wanted, actual, 1234L)
        assertTrue(status is AudioRouteStatus.RoutedElsewhere)
        assertFalse(status is AudioRouteStatus.Confirmed)
        assertEquals(actual, (status as AudioRouteStatus.RoutedElsewhere).actual)
        val confirmed = classifyObservedAudioRoute(wanted, wanted, 1235L)
        assertTrue(confirmed is AudioRouteStatus.Confirmed)
    }

    @Test
    fun selectedSecondaryUsbRemovedWhilePrimaryRemains_requiresRecovery() {
        val observer = FakeAudioRouteObserver()
        val source = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, source, observer)
        val primary = OutputDescriptor(101, "DAC A", AudioDeviceInfo.TYPE_USB_DEVICE)
        val secondary = OutputDescriptor(202, "DAC B", AudioDeviceInfo.TYPE_USB_HEADSET)
        source.updateDevices(listOf(primary, secondary))
        manager.selectDevice(202)
        var pauses = 0
        manager.onUsbDisconnectedListener = { pauses++ }

        source.updateDevices(listOf(primary))

        assertEquals(1, pauses)
        assertNull(manager.selectedDeviceId.value)
        assertNull(manager.preferredDeviceFlow.value)
        assertEquals(RouteFailure.DISCONNECTED, observer.lastFailedReason)
        assertTrue(manager.routeStatus.value is AudioRouteStatus.Failed)
        assertTrue(manager.outputDevices.value.none { it.isSelected })

        manager.selectDevice(101) // explicit recovery
        assertEquals(101, manager.selectedDeviceId.value)
        assertEquals(101, observer.lastRequested?.androidDeviceId)
    }

    @Test
    fun disconnectedDacWithSpeakerSelected_doesNotAutoPause() {
        val observer = FakeAudioRouteObserver()
        val source = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, source, observer)
        val speaker = OutputDescriptor(9, "Speaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val dac = OutputDescriptor(101, "DAC", AudioDeviceInfo.TYPE_USB_DEVICE)
        source.updateDevices(listOf(speaker, dac))
        manager.selectDevice(9)
        var pauses = 0
        manager.onUsbDisconnectedListener = { pauses++ }
        source.updateDevices(listOf(speaker))
        assertEquals(0, pauses)
        assertEquals(9, manager.selectedDeviceId.value)
    }

    @Test
    fun dacPresentDoesNotDeclareBitPerfectAutomaticallyTest() {
        val observer = FakeAudioRouteObserver()
        val fakeSource = FakeAudioDeviceSource()
        val manager = UsbDacManager(mockContext, fakeSource, observer)

        val dac = OutputDescriptor(androidDeviceId = 99, name = "Luxury DAC 768kHz 32bit", androidType = AudioDeviceInfo.TYPE_USB_DEVICE)
        fakeSource.updateDevices(listOf(dac))

        // Simply having a DAC name with "768kHz" does not make it Confirmed or Bit-Perfect verified
        assertFalse(manager.routeStatus.value is AudioRouteStatus.Confirmed)
        assertEquals(AudioRouteStatus.Requested(dac), manager.routeStatus.value)
    }
}
