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

class UsbDacManager(
    private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _dacState = MutableStateFlow(UsbDacInfo())
    val dacState: StateFlow<UsbDacInfo> = _dacState.asStateFlow()

    private val _outputDevices = MutableStateFlow<List<AudioOutputDevice>>(emptyList())
    val outputDevices: StateFlow<List<AudioOutputDevice>> = _outputDevices.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<Int?>(null)
    val selectedDeviceId: StateFlow<Int?> = _selectedDeviceId.asStateFlow()

    private val _preferredDeviceFlow = MutableStateFlow<AudioDeviceInfo?>(null)
    val preferredDeviceFlow: StateFlow<AudioDeviceInfo?> = _preferredDeviceFlow.asStateFlow()

    private var preferredUsbDevice: AudioDeviceInfo? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scanDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            scanDevices()
        }
    }

    init {
        try {
            audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            scanDevices()
        } catch (e: Exception) {
            Log.w(TAG, "Error registering AudioDeviceCallback", e)
        }
    }

    fun scanDevices() {
        val am = audioManager
        if (am == null) {
            _outputDevices.value = listOf(
                AudioOutputDevice(
                    id = 1,
                    name = "Altavoz del Dispositivo",
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    isSelected = true,
                    isSpeaker = true,
                ),
            )
            return
        }
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbDevice = devices.firstOrNull { isUsbAudioDevice(it) }

        if (usbDevice != null) {
            preferredUsbDevice = usbDevice
            val reportedSampleRates = usbDevice.sampleRates.toList()
            val channels = usbDevice.channelCounts.toList()
            val encodings = usbDevice.encodings.toList()

            _dacState.value = UsbDacInfo(
                isConnected = true,
                deviceName = usbDevice.productName?.toString().takeIf { !it.isNullOrBlank() } ?: "DAC USB Externo",
                sampleRates = reportedSampleRates,
                channelCounts = channels,
                encodings = encodings,
                deviceId = usbDevice.id,
            )
        } else {
            preferredUsbDevice = null
            _dacState.value = UsbDacInfo(isConnected = false)
        }

        val currentSelectedId = _selectedDeviceId.value
        val targetPreferredDevice = if (currentSelectedId != null) {
            devices.firstOrNull { it.id == currentSelectedId }
        } else {
            usbDevice
        }
        _preferredDeviceFlow.value = targetPreferredDevice

        val deviceList = devices.map { dev ->
            val isUsb = isUsbAudioDevice(dev)
            val isBt = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                dev.type == AudioDeviceInfo.TYPE_HEARING_AID
            val isWired = dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            val isSpk = dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                dev.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

            val name = when {
                !dev.productName.isNullOrBlank() -> dev.productName.toString()
                isUsb -> "DAC USB Audio"
                isBt -> "Auriculares Bluetooth"
                isWired -> "Auriculares Cableados"
                isSpk -> "Altavoz del Dispositivo"
                else -> "Salida de Audio"
            }

            val isSelected = when {
                currentSelectedId != null -> dev.id == currentSelectedId
                usbDevice != null -> dev.id == usbDevice.id
                else -> isSpk || isWired || isBt
            }

            AudioOutputDevice(
                id = dev.id,
                name = name,
                type = dev.type,
                isSelected = isSelected,
                isUsbDac = isUsb,
                isBluetooth = isBt,
                isWired = isWired,
                isSpeaker = isSpk,
                sampleRates = dev.sampleRates.toList(),
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
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }

    fun release() {
        try {
            audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering AudioDeviceCallback", e)
        }
    }
}
