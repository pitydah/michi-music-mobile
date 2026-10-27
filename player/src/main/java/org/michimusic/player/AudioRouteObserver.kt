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
    // Implementations without an AudioTrack (e.g. fake test sources) may ignore playback transitions.
    fun onPlaybackStateChanged(isPlaying: Boolean) {}
    fun attachAudioTrack(audioTrack: AudioTrack)
    fun detachAudioTrack()
    fun release()
}

class DefaultAudioRouteObserver : AudioRouteObserver {

    private val _routeStatus = MutableStateFlow<AudioRouteStatus>(AudioRouteStatus.NoDevice)
    override val routeStatus: StateFlow<AudioRouteStatus> = _routeStatus.asStateFlow()

    private val evidenceGate = PlaybackRouteEvidenceGate()
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
        // Never downgrade an actual observation or an error to mere detection.
        // A new user selection is handled separately by onDeviceRequested().
        if (current == AudioRouteStatus.NoDevice || current is AudioRouteStatus.Detected) {
            _routeStatus.value = AudioRouteStatus.Detected(descriptor)
        }
    }

    override fun onDeviceRequested(descriptor: OutputDescriptor?) {
        currentRequestedDescriptor = descriptor
        if (descriptor == null) {
            // Releasing a preference does not prove which device is now playing.
            _routeStatus.value = AudioRouteStatus.NoDevice
            verifyRoutingIfPossible()
            return
        }
        _routeStatus.value = AudioRouteStatus.Requested(descriptor)
        verifyRoutingIfPossible()
    }

    override fun onRoutingFailed(deviceId: Int?, failure: RouteFailure) {
        _routeStatus.value = AudioRouteStatus.Failed(deviceId, failure)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        evidenceGate.onPlaybackChanged(isPlaying)
        if (isPlaying) {
            verifyRoutingIfPossible()
        } else {
            val previous = _routeStatus.value
            when (previous) {
                is AudioRouteStatus.Confirmed -> _routeStatus.value = AudioRouteStatus.LastObserved(previous.device, previous.observedAtMs)
                is AudioRouteStatus.RoutedElsewhere -> _routeStatus.value = AudioRouteStatus.LastObserved(previous.actual, previous.observedAtMs)
                else -> Unit
            }
        }
    }

    override fun attachAudioTrack(audioTrack: AudioTrack) {
        detachAudioTrack()
        observedAudioTrack = audioTrack

        val listener = AudioRouting.OnRoutingChangedListener { routing ->
            // Android may deliver an already queued callback after a hot-swap.
            // Never attribute the OLD AudioTrack route to the newly attached one.
            if (observedAudioTrack === audioTrack) {
                if (evidenceGate.isCurrent(audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING)) {
                    handleRoutedDevice(routing.routedDevice)
                } else {
                    onPlaybackStateChanged(false)
                }
            }
        }
        routingListener = listener
        try {
            audioTrack.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
            // An initialized AudioTrack is not necessarily playing yet.
            verifyRoutingIfPossible()
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
        val prior = _routeStatus.value
        when (prior) {
            is AudioRouteStatus.Confirmed -> _routeStatus.value = AudioRouteStatus.LastObserved(prior.device, prior.observedAtMs)
            is AudioRouteStatus.RoutedElsewhere -> _routeStatus.value = AudioRouteStatus.LastObserved(prior.actual, prior.observedAtMs)
            else -> Unit
        }
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

        _routeStatus.value = classifyObservedAudioRoute(
            requested = requested,
            actual = observedDescriptor,
            observedAtMs = System.currentTimeMillis(),
        )
    }

    private fun verifyRoutingIfPossible() {
        val track = observedAudioTrack
        val requested = currentRequestedDescriptor
        if (track != null && evidenceGate.isCurrent(track.playState == AudioTrack.PLAYSTATE_PLAYING)) {
            val routed = track.routedDevice
            if (routed != null) {
                handleRoutedDevice(routed)
                return
            }
        }
        // A stopped/paused track cannot confirm the *current* route.
        // Preserve a historical observation unless the user explicitly changes selection.
        if (requested != null && _routeStatus.value !is AudioRouteStatus.LastObserved &&
            _routeStatus.value !is AudioRouteStatus.Failed) {
            _routeStatus.value = AudioRouteStatus.Requested(requested)
        } else if (requested == null && _routeStatus.value is AudioRouteStatus.Confirmed) {
            val prior = _routeStatus.value as AudioRouteStatus.Confirmed
            _routeStatus.value = AudioRouteStatus.LastObserved(prior.device, prior.observedAtMs)
        }
    }

    override fun release() {
        evidenceGate.reset()
        detachAudioTrack()
        _routeStatus.value = AudioRouteStatus.NoDevice
        currentRequestedDescriptor = null
    }
}
