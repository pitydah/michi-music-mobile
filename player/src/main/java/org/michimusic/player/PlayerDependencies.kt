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
    val pcmFormatFlow: Flow<PcmFormat?>
        get() = mockPcmFormatFlow ?: rtpPcmAudioTap?.currentFormat ?: emptyFlow()

    @JvmStatic
    fun getActivePcmFormat(): PcmFormat? = mockPcmFormatForTesting ?: rtpPcmAudioTap?.activeFormat

    @JvmStatic
    fun resetTestingOverrides() {
        stopPcmStreaming() // test lifecycle must not leak a worker thread or muted output
        mockPcmFormatFlow = null
        mockPcmFormatForTesting = null
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
            tap.startSender(enqueue)
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
    fun resumePcmStreaming() {
        val tap = rtpPcmAudioTap ?: return
        if (tap.hasDeliveryFault || tap.activeFormat == null) return
        // Caller must have completed format negotiation before invoking resume.
        tap.isPaused = false
        tap.isRenegotiating = false
    }

    @JvmStatic
    val pcmStreamingDeliveryFailed: Boolean
        get() = rtpPcmAudioTap?.hasDeliveryFault == true

    @JvmStatic
    fun setMuteLocalOutput(muted: Boolean) {
        rtpPcmAudioTap?.muteLocalOutput = muted
    }
}
