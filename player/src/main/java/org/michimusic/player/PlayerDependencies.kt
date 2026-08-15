package org.michimusic.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.michimusic.core.models.PcmFormat
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.ReplayGainDao

object PlayerDependencies {
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
        mockPcmFormatFlow = null
        mockPcmFormatForTesting = null
    }

    @JvmStatic
    fun startPcmStreaming(listener: (ByteArray) -> Unit) {
        val tap = rtpPcmAudioTap ?: return
        tap.pcmChunkListener = listener
        tap.isEnabled = true
        tap.muteLocalOutput = true
    }

    @JvmStatic
    fun stopPcmStreaming() {
        val tap = rtpPcmAudioTap ?: return
        tap.isEnabled = false
        tap.isPaused = false
        tap.isRenegotiating = false
        tap.muteLocalOutput = false
        tap.pcmChunkListener = null
    }

    @JvmStatic
    fun pausePcmStreaming() {
        rtpPcmAudioTap?.isPaused = true
    }

    @JvmStatic
    fun resumePcmStreaming() {
        val tap = rtpPcmAudioTap ?: return
        tap.isPaused = false
        tap.isRenegotiating = false
    }

    @JvmStatic
    fun setMuteLocalOutput(muted: Boolean) {
        rtpPcmAudioTap?.muteLocalOutput = muted
    }
}
