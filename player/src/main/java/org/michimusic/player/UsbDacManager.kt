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
    val isUsbHeadset: Boolean = false,
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
    val isUsbHeadset: Boolean = false,
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
    // Android device IDs are ephemeral. A missing selected USB route requires user recovery.
    private var lastKnownUsbDeviceId: Int? = null
    private var recoveryRequired = false
    // Repeated AudioDeviceCallback emissions must not erase observed routing truth.
    private var issuedRouteRequest = false
    private var lastIssuedDescriptor: OutputDescriptor? = null

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
        val nativeDevices = try {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query output devices", e)
            emptyList()
        }

        val connectedIds = descriptors.map { it.androidDeviceId }.toSet()
        val explicitId = _selectedDeviceId.value
        val lostId = missingSelectedUsbOutput(explicitId, lastKnownUsbDeviceId, connectedIds)
        if (lostId != null && !recoveryRequired) {
            // Pause BEFORE Android silently changes to a speaker or a different DAC.
            recoveryRequired = true
            issuedRouteRequest = false
            lastIssuedDescriptor = null
            _selectedDeviceId.value = null
            _preferredDeviceFlow.value = null
            routeObserver.onRoutingFailed(lostId, RouteFailure.DISCONNECTED)
            try {
                onUsbDisconnectedListener?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Disconnect callback failed", e)
            }
        }

        val selectedId = _selectedDeviceId.value
        val selectedDescriptor = selectedId?.let { id ->
            descriptors.firstOrNull { it.androidDeviceId == id }
        }
        val automaticUsb = descriptors.firstOrNull { isUsbAudioType(it.androidType) }
        val desiredDescriptor = if (recoveryRequired) null else selectedDescriptor ?: automaticUsb
        lastKnownUsbDeviceId = desiredDescriptor?.takeIf { isUsbAudioType(it.androidType) }?.androidDeviceId

        val displayedUsb = selectedDescriptor?.takeIf { isUsbAudioType(it.androidType) }
            ?: automaticUsb
        _dacState.value = if (displayedUsb != null) {
            UsbDacInfo(
                isConnected = true,
                deviceName = displayedUsb.name.ifBlank { "DAC USB Externo" },
                sampleRates = displayedUsb.announcedSampleRates,
                channelCounts = displayedUsb.announcedChannelCounts,
                encodings = displayedUsb.announcedEncodings,
                deviceId = displayedUsb.androidDeviceId,
                isUsbHeadset = displayedUsb.androidType == AudioDeviceInfo.TYPE_USB_HEADSET,
            )
        } else {
            UsbDacInfo()
        }

        val nativePreferred = desiredDescriptor?.let { desired ->
            nativeDevices.firstOrNull { it.id == desired.androidDeviceId }
        }
        preferredUsbDevice = nativePreferred?.takeIf { isUsbAudioType(it.type) }
        _preferredDeviceFlow.value = nativePreferred
        if (!recoveryRequired &&
            (!issuedRouteRequest || !sameRequestedRoute(lastIssuedDescriptor, desiredDescriptor))) {
            // Only actual selection changes issue a new request. A routine USB rescan
            // must not demote a CONFIRMED or ROUTED_ELSEWHERE observation.
            issuedRouteRequest = true
            lastIssuedDescriptor = desiredDescriptor
            if (desiredDescriptor == null) {
                routeObserver.onDeviceRequested(null)
            } else {
                routeObserver.onDeviceDetected(desiredDescriptor)
                routeObserver.onDeviceRequested(desiredDescriptor)
            }
        }

        val deviceList = descriptors.map { desc ->
            val isUsb = desc.androidType == AudioDeviceInfo.TYPE_USB_DEVICE ||
                desc.androidType == AudioDeviceInfo.TYPE_USB_ACCESSORY
            val headset = desc.androidType == AudioDeviceInfo.TYPE_USB_HEADSET
            val bluetooth = desc.androidType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                desc.androidType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                desc.androidType == AudioDeviceInfo.TYPE_HEARING_AID
            val wired = desc.androidType == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                desc.androidType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            val speaker = desc.androidType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                desc.androidType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            AudioOutputDevice(
                id = desc.androidDeviceId,
                name = desc.name,
                type = desc.androidType,
                // A request is only a preference, not yet confirmed by AudioTrack.
                isSelected = !recoveryRequired && desiredDescriptor?.androidDeviceId == desc.androidDeviceId,
                isUsbDac = isUsb,
                isUsbHeadset = headset,
                isBluetooth = bluetooth,
                isWired = wired,
                isSpeaker = speaker,
                sampleRates = desc.announcedSampleRates,
            )
        }
        _outputDevices.value = if (deviceList.isEmpty()) {
            listOf(AudioOutputDevice(
                id = 1,
                name = "Altavoz del Dispositivo",
                type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                isSelected = !recoveryRequired,
                isSpeaker = true,
            ))
        } else deviceList
    }

    fun selectDevice(deviceId: Int) {
        // Reject stale or synthetic IDs: merely requesting a route cannot create a device.
        if (source.getOutputDevices().none { it.androidDeviceId == deviceId }) {
            routeObserver.onRoutingFailed(deviceId, RouteFailure.DEVICE_NOT_FOUND)
            return
        }
        recoveryRequired = false
        issuedRouteRequest = false // explicit reselect is a genuine retry
        _selectedDeviceId.value = deviceId
        scanDevices()
    }

    fun clearDeviceSelection() {
        issuedRouteRequest = false // user deliberately returns to automatic routing
        recoveryRequired = false
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
