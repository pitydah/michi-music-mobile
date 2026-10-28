package org.michimusic.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.michimusic.core.models.PcmFormat
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.ReplayGainDao

object PlayerDependencies {
    @JvmStatic
    var trackDao: org.michimusic.data.cache.TrackDao? = null
    @JvmStatic
    var replayGainDao: ReplayGainDao? = null
    @JvmStatic
    var appDao: AppDao? = null
    @JvmStatic
    var audioEffects: MichiAudioEffects? = null
    @JvmStatic
    var usbDacManager: UsbDacManager? = null
    @JvmStatic
    var rtpPcmAudioTap: RtpPcmAudioTap? = null
    @JvmStatic
    var replayGainProcessor: ReplayGainAudioProcessor? = null

    @JvmStatic
    val replayGainMode: ReplayGainMode
        get() = replayGainProcessor?.currentMode ?: ReplayGainMode.OFF

    @JvmStatic
    val replayGainPreAmp: ReplayGainPreAmp
        get() = replayGainProcessor?.currentPreAmp ?: ReplayGainPreAmp()

    @JvmStatic
    fun configureReplayGain(mode: ReplayGainMode, preAmp: ReplayGainPreAmp) {
        replayGainProcessor?.configure(mode, preAmp)
    }

    @JvmStatic
    var audioModeManager: AudioModeManager? = null
    @JvmStatic
    var signalTruthTracker: SignalTruthTracker? = null

    @JvmStatic
    var mockPcmFormatFlow: MutableStateFlow<PcmFormat?>? = null

    @JvmStatic
    var mockPcmFormatForTesting: PcmFormat? = null

    @JvmStatic
    var mockResumePcmStreamingResultForTesting: Boolean? = null

    @JvmStatic
    val pcmFormatFlow: Flow<PcmFormat?>
        get() = mockPcmFormatFlow ?: rtpPcmAudioTap?.currentFormat ?: emptyFlow()

    @JvmStatic
    fun getActivePcmFormat(): PcmFormat? = mockPcmFormatForTesting ?: rtpPcmAudioTap?.activeFormat

    @JvmStatic
    fun resetTestingOverrides() {
        stopPcmStreaming() // test lifecycle must not leak a worker thread or muted output
        mockPcmFormatFlow = null
        mockPcmFormatForTesting = null
        mockResumePcmStreamingResultForTesting = null
        signalTruthTracker = null
        audioModeManager = null
        rtpPcmAudioTap = null
    }

    // A network callback may block indefinitely. NEVER invoke it on the Media3
    // audio-renderer thread: hand off through a bounded, nonblocking queue.
    private val networkLock = Any()
    private var networkWorker: BoundedPcmSender? = null

    @JvmStatic
    fun startPcmStreaming(listener: (ByteArray) -> Unit) {
        startPcmStreaming(listener, null)
    }

    @JvmStatic
    fun startPcmStreaming(listener: (ByteArray) -> Unit, sessionToken: String?) {
        synchronized(networkLock) {
            networkWorker?.close()
            networkWorker = null
            val tap = rtpPcmAudioTap ?: return
            var worker: BoundedPcmSender? = null
            // The callback identity is unique to this sender generation.
            val enqueue: (ByteArray) -> Unit = { bytes -> worker?.offer(bytes) }
            val next = BoundedPcmSender(
                send = listener,
                onFailure = { tap.reportSenderFailureIfCurrent(enqueue) },
            )
            worker = next
            tap.onFormatDiscontinuity = { next.discardPending() }
            tap.startSender(enqueue, sessionToken = sessionToken)
            networkWorker = next
            next.start()
        }
    }

    @JvmStatic
    fun stopPcmStreaming() {
        synchronized(networkLock) {
            rtpPcmAudioTap?.let { tap ->
                tap.stopSender()
                tap.onFormatDiscontinuity = null
            }
            networkWorker?.close()
            networkWorker = null
        }
    }

    @JvmStatic
    fun pausePcmStreaming() {
        synchronized(networkLock) {
            rtpPcmAudioTap?.isPaused = true
            networkWorker?.discardPending()
        }
    }

    @JvmStatic
    fun resumePcmStreaming(format: org.michimusic.core.models.PcmFormat, sessionToken: String? = null): Boolean {
        mockResumePcmStreamingResultForTesting?.let { return it }
        val tap = rtpPcmAudioTap ?: return false
        if (tap.hasDeliveryFault) return false
        if (tap.isRenegotiating) {
            val success = tap.completeRenegotiation(format, sessionToken)
            if (!success) return false
        } else {
            if (tap.activeFormat != null && tap.activeFormat != format) return false
            val activeToken = tap.activeSessionToken
            if (activeToken != null && sessionToken != null && activeToken != sessionToken) return false
            if (activeToken != null && sessionToken == null) return false
        }
        tap.isPaused = false
        return true
    }

    @JvmStatic
    val pcmStreamingDeliveryFailed: Boolean
        get() = rtpPcmAudioTap?.hasDeliveryFault == true

    @JvmStatic
    fun setMuteLocalOutput(muted: Boolean) {
        rtpPcmAudioTap?.muteLocalOutput = muted
    }
}
