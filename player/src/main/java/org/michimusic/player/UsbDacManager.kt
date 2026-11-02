package org.michimusic.player

import android.content.Context
import android.media.AudioAttributes
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

data class UsbMixerState(
    val deviceId: Int? = null,
    val supportedRates: List<Int> = emptyList(),
    val requestedRate: Int? = null,
    val message: String = "",
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
    private val mixerPreferences = context.getSharedPreferences("michi_usb_mixer_v1", Context.MODE_PRIVATE)
    // Building this eagerly crashed every JVM unit test: the Android stubs return
    // null from AudioAttributes.Builder.setUsage(). The attributes are only needed
    // for the API 34 mixer calls, so resolve them on first use and degrade to null.
    private val mediaAttributes: AudioAttributes? by lazy {
        runCatching {
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        }.getOrNull()
    }
    private val _mixerState = MutableStateFlow(UsbMixerState())
    val mixerState: StateFlow<UsbMixerState> = _mixerState.asStateFlow()
    private var lastMixerDevice: AudioDeviceInfo? = null

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

    /** Only currently observed USB routes can be configured. A request is NOT hardware proof. */
    fun requestMixerRate(sampleRate: Int?): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            _mixerState.value = _mixerState.value.copy(message = "Requiere Android 14 o posterior")
            return false
        }
        val observed = (routeStatus.value as? AudioRouteStatus.Confirmed)?.device
        val device = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.id == observed?.androidDeviceId && isUsbAudioType(it.type) }
        if (device == null) {
            _mixerState.value = _mixerState.value.copy(message = "Reproduce por USB y confirma la ruta primero")
            return false
        }
        return runCatching {
            val supported = audioManager?.getSupportedMixerAttributes(device).orEmpty()
            if (sampleRate == null) {
                val cleared = audioManager?.let { manager ->
                    mediaAttributes?.let { attributes ->
                        manager.clearPreferredMixerAttributes(attributes, device)
                    }
                } == true
                if (cleared) mixerPreferences.edit().remove("rate_${device.productName}_${device.type}").apply()
                _mixerState.value = _mixerState.value.copy(requestedRate = null,
                    message = if (cleared) "Preferencia eliminada en Android" else "No se pudo borrar la preferencia")
                cleared
            } else {
                val match = supported.firstOrNull { it.format.sampleRate == sampleRate }
                if (match == null) {
                    _mixerState.value = _mixerState.value.copy(message = "Esta frecuencia no está ofrecida por Android")
                    false
                } else {
                    val accepted = audioManager?.let { manager ->
                        mediaAttributes?.let { attributes ->
                            manager.setPreferredMixerAttributes(attributes, device, match)
                        }
                    } == true
                    if (accepted) mixerPreferences.edit().putInt("rate_${device.productName}_${device.type}", sampleRate).apply()
                    _mixerState.value = _mixerState.value.copy(requestedRate = if (accepted) sampleRate else null,
                        message = if (accepted) "Preferencia aceptada por Android; reinicia la salida para que se aplique. DAC físico no medido"
                        else "Android rechazó la preferencia")
                    accepted
                }
            }
        }.getOrElse { error ->
            _mixerState.value = _mixerState.value.copy(message = "No se pudo configurar: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun refreshMixerCaps(desired: AudioDeviceInfo?) {
        if (lastMixerDevice?.id != desired?.id && android.os.Build.VERSION.SDK_INT >= 34) {
            runCatching {
                val attributes = mediaAttributes
                if (attributes != null) {
                    lastMixerDevice?.let { audioManager?.clearPreferredMixerAttributes(attributes, it) }
                }
            }
        }
        lastMixerDevice = desired
        val rates = if (android.os.Build.VERSION.SDK_INT >= 34 && desired != null && isUsbAudioType(desired.type)) {
            runCatching { audioManager?.getSupportedMixerAttributes(desired).orEmpty().map { it.format.sampleRate }
                .filter { it > 0 }.distinct().sorted().take(12) }.getOrDefault(emptyList())
        } else emptyList()
        if (_mixerState.value.deviceId != desired?.id) {
            _mixerState.value = UsbMixerState(deviceId = desired?.id, supportedRates = rates,
                message = if (desired == null) "Sin salida USB seleccionada" else
                    if (android.os.Build.VERSION.SDK_INT < 34) "Android 14+ necesario para mezclar" else
                    "Configuración opcional; no se ha solicitado un cambio")
        } else if (_mixerState.value.supportedRates != rates) {
            _mixerState.value = _mixerState.value.copy(supportedRates = rates)
        }
    }

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
        refreshMixerCaps(preferredUsbDevice)
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
