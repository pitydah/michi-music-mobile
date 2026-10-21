package org.michimusic.player

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AudioRouteObserver"

/**
 * Observa la ruta física efectiva de audio del sistema/reproductor.
 * Evita la asunción de ruta confirmada cuando no hay verificación de bajo nivel.
 */
interface AudioRouteObserver {
    val routeStatus: StateFlow<AudioRouteStatus>
    fun onDeviceRequested(descriptor: OutputDescriptor?)
    fun onDeviceDetected(descriptor: OutputDescriptor?)
    fun onRoutingFailed(deviceId: Int?, failure: RouteFailure)
    fun attachAudioTrack(audioTrack: AudioTrack)
    fun detachAudioTrack()
    fun release()
}

class DefaultAudioRouteObserver : AudioRouteObserver {

    private val _routeStatus = MutableStateFlow<AudioRouteStatus>(AudioRouteStatus.NoDevice)
    override val routeStatus: StateFlow<AudioRouteStatus> = _routeStatus.asStateFlow()

    private var currentRequestedDescriptor: OutputDescriptor? = null
    private var observedAudioTrack: AudioTrack? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    override fun onDeviceDetected(descriptor: OutputDescriptor?) {
        if (descriptor == null) {
            _routeStatus.value = AudioRouteStatus.NoDevice
            currentRequestedDescriptor = null
            return
        }
        val current = _routeStatus.value
        if (current !is AudioRouteStatus.Requested && current !is AudioRouteStatus.Confirmed) {
            _routeStatus.value = AudioRouteStatus.Detected(descriptor)
        }
    }

    override fun onDeviceRequested(descriptor: OutputDescriptor?) {
        currentRequestedDescriptor = descriptor
        if (descriptor == null) {
            _routeStatus.value = AudioRouteStatus.NoDevice
            return
        }
        _routeStatus.value = AudioRouteStatus.Requested(descriptor)
        verifyRoutingIfPossible()
    }

    override fun onRoutingFailed(deviceId: Int?, failure: RouteFailure) {
        _routeStatus.value = AudioRouteStatus.Failed(deviceId, failure)
    }

    override fun attachAudioTrack(audioTrack: AudioTrack) {
        detachAudioTrack()
        observedAudioTrack = audioTrack

        val listener = AudioRouting.OnRoutingChangedListener { routing ->
            val routedDevice = routing.routedDevice
            handleRoutedDevice(routedDevice)
        }
        routingListener = listener
        try {
            audioTrack.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
            val currentRoutedDevice = audioTrack.routedDevice
            if (currentRoutedDevice != null) {
                handleRoutedDevice(currentRoutedDevice)
            } else {
                verifyRoutingIfPossible()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error registering OnRoutingChangedListener on AudioTrack", e)
            verifyRoutingIfPossible()
        }
    }

    override fun detachAudioTrack() {
        val track = observedAudioTrack
        val listener = routingListener
        if (track != null && listener != null) {
            try {
                track.removeOnRoutingChangedListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering OnRoutingChangedListener", e)
            }
        }
        observedAudioTrack = null
        routingListener = null
    }

    private fun handleRoutedDevice(routedDevice: AudioDeviceInfo?) {
        val requested = currentRequestedDescriptor
        if (routedDevice == null) {
            if (requested != null) {
                _routeStatus.value = AudioRouteStatus.Unknown("AudioTrack routedDevice es nulo")
            }
            return
        }

        val observedDescriptor = OutputDescriptor(
            androidDeviceId = routedDevice.id,
            name = routedDevice.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Dispositivo ${routedDevice.id}",
            androidType = routedDevice.type,
            announcedSampleRates = routedDevice.sampleRates.toList(),
            announcedChannelCounts = routedDevice.channelCounts.toList(),
            announcedEncodings = routedDevice.encodings.toList(),
        )

        if (requested == null || requested.androidDeviceId == routedDevice.id) {
            _routeStatus.value = AudioRouteStatus.Confirmed(
                device = observedDescriptor,
                observedAtMs = System.currentTimeMillis(),
            )
        } else {
            // Se solicitó un dispositivo pero Android enrutó hacia otro diferente
            _routeStatus.value = AudioRouteStatus.Confirmed(
                device = observedDescriptor,
                observedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun verifyRoutingIfPossible() {
        val track = observedAudioTrack
        val requested = currentRequestedDescriptor
        if (track != null) {
            val routed = track.routedDevice
            if (routed != null) {
                handleRoutedDevice(routed)
                return
            }
        }
        // Si no hay AudioTrack observada aún, mantener Requested o pasar a Unknown
        if (requested != null && _routeStatus.value !is AudioRouteStatus.Confirmed) {
            _routeStatus.value = AudioRouteStatus.Requested(requested)
        }
    }

    override fun release() {
        detachAudioTrack()
        _routeStatus.value = AudioRouteStatus.NoDevice
        currentRequestedDescriptor = null
    }
}
