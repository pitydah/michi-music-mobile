package org.michimusic.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "UsbDacManager"

data class UsbDacInfo(
    val isConnected: Boolean = false,
    val deviceName: String = "",
    val sampleRates: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    val encodings: List<Int> = emptyList(),
    val isBitPerfectSupported: Boolean = false,
    val deviceId: Int = 0,
)

class UsbDacManager(
    private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val _dacState = MutableStateFlow(UsbDacInfo())
    val dacState: StateFlow<UsbDacInfo> = _dacState.asStateFlow()

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
        val am = audioManager ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbDevice = devices.firstOrNull { isUsbAudioDevice(it) }

        if (usbDevice != null) {
            preferredUsbDevice = usbDevice
            val sampleRates = usbDevice.sampleRates.toList().ifEmpty { listOf(44100, 48000, 96000, 192000) }
            val channels = usbDevice.channelCounts.toList().ifEmpty { listOf(2) }
            val encodings = usbDevice.encodings.toList()
            val isBitPerfect = sampleRates.any { it >= 96000 } || encodings.any { it == 3 || it == 4 } // ENCODING_PCM_FLOAT / 24BIT / 32BIT

            _dacState.value = UsbDacInfo(
                isConnected = true,
                deviceName = usbDevice.productName?.toString().takeIf { !it.isNullOrBlank() } ?: "DAC USB Externo",
                sampleRates = sampleRates,
                channelCounts = channels,
                encodings = encodings,
                isBitPerfectSupported = isBitPerfect,
                deviceId = usbDevice.id,
            )
            Log.i(TAG, "USB DAC detectado: ${_dacState.value.deviceName} (Sample rates: $sampleRates)")
        } else {
            preferredUsbDevice = null
            _dacState.value = UsbDacInfo(isConnected = false)
        }
    }

    fun getPreferredUsbDevice(): AudioDeviceInfo? = preferredUsbDevice

    private fun isUsbAudioDevice(device: AudioDeviceInfo): Boolean {
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
