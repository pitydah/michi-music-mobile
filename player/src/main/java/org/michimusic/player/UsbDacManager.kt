package org.michimusic.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "UsbDacManager"

data class AudioOutputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val isSelected: Boolean = false,
    val isUsbDac: Boolean = false,
    val isBluetooth: Boolean = false,
    val isWired: Boolean = false,
    val isSpeaker: Boolean = false,
    val sampleRates: List<Int> = emptyList(),
)

data class UsbDacInfo(
    val isConnected: Boolean = false,
    val deviceName: String = "",
    val sampleRates: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    val encodings: List<Int> = emptyList(),
    val deviceId: Int = 0,
)

/**
 * Fuente por defecto basada en AudioManager del sistema.
 */
class SystemAudioDeviceSource(
    private val audioManager: AudioManager?,
) : AudioDeviceSource {

    private val listeners = mutableListOf<AudioDeviceSource.DeviceChangeListener>()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            notifyChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            notifyChanged()
        }
    }

    private var registered = false

    init {
        try {
            audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Error registering AudioDeviceCallback", e)
        }
    }

    private fun notifyChanged() {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { it.onDevicesChanged() }
    }

    override fun getOutputDevices(): List<OutputDescriptor> {
        val am = audioManager ?: return emptyList()
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { dev ->
                OutputDescriptor(
                    androidDeviceId = dev.id,
                    name = dev.productName?.toString().takeIf { !it.isNullOrBlank() }
                        ?: resolveFallbackDeviceName(dev.type),
                    androidType = dev.type,
                    announcedSampleRates = dev.sampleRates.toList(),
                    announcedChannelCounts = dev.channelCounts.toList(),
                    announcedEncodings = dev.encodings.toList(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching output devices", e)
            emptyList()
        }
    }

    override fun addListener(listener: AudioDeviceSource.DeviceChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    override fun removeListener(listener: AudioDeviceSource.DeviceChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun release() {
        if (registered) {
            try {
                audioManager?.unregisterAudioDeviceCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering AudioDeviceCallback", e)
            }
            registered = false
        }
        synchronized(listeners) {
            listeners.clear()
        }
    }

    private fun resolveFallbackDeviceName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "DAC USB Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID -> "Auriculares Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares Cableados"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Altavoz del Dispositivo"
            else -> "Salida de Audio"
        }
    }
}

class UsbDacManager(
    context: Context,
    deviceSource: AudioDeviceSource? = null,
    private val routeObserver: AudioRouteObserver = DefaultAudioRouteObserver(),
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val source: AudioDeviceSource = deviceSource ?: SystemAudioDeviceSource(audioManager)

    private val _dacState = MutableStateFlow(UsbDacInfo())
    val dacState: StateFlow<UsbDacInfo> = _dacState.asStateFlow()

    private val _outputDevices = MutableStateFlow<List<AudioOutputDevice>>(emptyList())
    val outputDevices: StateFlow<List<AudioOutputDevice>> = _outputDevices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<Int?>(null)
    val selectedDeviceId: StateFlow<Int?> = _selectedDeviceId.asStateFlow()

    private val _preferredDeviceFlow = MutableStateFlow<AudioDeviceInfo?>(null)
    val preferredDeviceFlow: StateFlow<AudioDeviceInfo?> = _preferredDeviceFlow.asStateFlow()

    val routeStatus: StateFlow<AudioRouteStatus> = routeObserver.routeStatus

    /**
     * Listener invocado cuando un dispositivo de salida USB se desconecta.
     * Utilizado para pausar la reproducción de inmediato y prevenir sonido abrupto por el altavoz.
     */
    var onUsbDisconnectedListener: (() -> Unit)? = null

    private var preferredUsbDevice: AudioDeviceInfo? = null
    private var lastKnownUsbDeviceId: Int? = null

    private val deviceChangeListener = object : AudioDeviceSource.DeviceChangeListener {
        override fun onDevicesChanged() {
            scanDevices()
        }
    }

    init {
        source.addListener(deviceChangeListener)
        scanDevices()
    }

    fun getAudioRouteObserver(): AudioRouteObserver = routeObserver

    fun scanDevices() {
        val descriptors = source.getOutputDevices()
        val am = audioManager
        val nativeDevices = try {
            am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val usbDescriptor = descriptors.firstOrNull { isUsbAudioType(it.androidType) }
        val usbNativeDevice = nativeDevices.firstOrNull { isUsbAudioType(it.type) }

        // Detección de desconexión de USB para auto-pausa
        if (usbDescriptor == null && lastKnownUsbDeviceId != null) {
            val wasSelected = _selectedDeviceId.value == lastKnownUsbDeviceId || _selectedDeviceId.value == null
            lastKnownUsbDeviceId = null
            preferredUsbDevice = null
            _dacState.value = UsbDacInfo(isConnected = false)
            _selectedDeviceId.value = null
            routeObserver.onRoutingFailed(null, RouteFailure.DISCONNECTED)
            if (wasSelected) {
                try {
                    onUsbDisconnectedListener?.invoke()
                } catch (e: Exception) {
                    Log.w(TAG, "Error invoking onUsbDisconnectedListener", e)
                }
            }
        }

        if (usbDescriptor != null) {
            lastKnownUsbDeviceId = usbDescriptor.androidDeviceId
            preferredUsbDevice = usbNativeDevice
            _dacState.value = UsbDacInfo(
                isConnected = true,
                deviceName = usbDescriptor.name.takeIf { it.isNotBlank() } ?: "DAC USB Externo",
                sampleRates = usbDescriptor.announcedSampleRates,
                channelCounts = usbDescriptor.announcedChannelCounts,
                encodings = usbDescriptor.announcedEncodings,
                deviceId = usbDescriptor.androidDeviceId,
            )
            routeObserver.onDeviceDetected(usbDescriptor)
        } else if (lastKnownUsbDeviceId == null) {
            preferredUsbDevice = null
            _dacState.value = UsbDacInfo(isConnected = false)
        }

        val currentSelectedId = _selectedDeviceId.value
        val targetPreferredDevice = if (currentSelectedId != null) {
            nativeDevices.firstOrNull { it.id == currentSelectedId }
        } else {
            usbNativeDevice
        }
        _preferredDeviceFlow.value = targetPreferredDevice

        val selectedDescriptor = if (currentSelectedId != null) {
            descriptors.firstOrNull { it.androidDeviceId == currentSelectedId }
        } else {
            usbDescriptor
        }
        if (selectedDescriptor != null) {
            routeObserver.onDeviceRequested(selectedDescriptor)
        } else if (descriptors.isEmpty() && lastKnownUsbDeviceId == null && _dacState.value.isConnected) {
            routeObserver.onDeviceRequested(null)
        }

        val deviceList = descriptors.map { desc ->
            val isUsb = isUsbAudioType(desc.androidType)
            val isBt = desc.androidType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                desc.androidType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                desc.androidType == AudioDeviceInfo.TYPE_HEARING_AID
            val isWired = desc.androidType == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                desc.androidType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            val isSpk = desc.androidType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                desc.androidType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

            val isSelected = when {
                currentSelectedId != null -> desc.androidDeviceId == currentSelectedId
                usbDescriptor != null -> desc.androidDeviceId == usbDescriptor.androidDeviceId
                else -> isSpk || isWired || isBt
            }

            AudioOutputDevice(
                id = desc.androidDeviceId,
                name = desc.name,
                type = desc.androidType,
                isSelected = isSelected,
                isUsbDac = isUsb,
                isBluetooth = isBt,
                isWired = isWired,
                isSpeaker = isSpk,
                sampleRates = desc.announcedSampleRates,
            )
        }

        _outputDevices.value = if (deviceList.isEmpty()) {
            listOf(
                AudioOutputDevice(
                    id = 1,
                    name = "Altavoz del Dispositivo",
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    isSelected = true,
                    isSpeaker = true,
                ),
            )
        } else {
            deviceList
        }
    }

    fun selectDevice(deviceId: Int) {
        _selectedDeviceId.value = deviceId
        scanDevices()
    }

    fun clearDeviceSelection() {
        _selectedDeviceId.value = null
        scanDevices()
    }

    fun getPreferredUsbDevice(): AudioDeviceInfo? = preferredUsbDevice

    fun isUsbAudioDevice(device: AudioDeviceInfo): Boolean {
        return isUsbAudioType(device.type)
    }

    private fun isUsbAudioType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }

    fun release() {
        source.removeListener(deviceChangeListener)
        if (source is SystemAudioDeviceSource) {
            source.release()
        }
        routeObserver.release()
    }
}
