package org.michimusic.player

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
        tap.muteLocalOutput = false
        tap.pcmChunkListener = null
    }

    @JvmStatic
    fun pausePcmStreaming() {
        rtpPcmAudioTap?.isPaused = true
    }

    @JvmStatic
    fun resumePcmStreaming() {
        rtpPcmAudioTap?.isPaused = false
    }

    @JvmStatic
    fun setMuteLocalOutput(muted: Boolean) {
        rtpPcmAudioTap?.muteLocalOutput = muted
    }

    @JvmStatic
    fun getActiveSampleRate(): Int = rtpPcmAudioTap?.currentSampleRate ?: 48000

    @JvmStatic
    fun getActiveChannels(): Int = rtpPcmAudioTap?.currentChannelCount ?: 2

    @JvmStatic
    fun getActiveBitDepth(): Int = rtpPcmAudioTap?.bitDepth ?: 16

    @JvmStatic
    fun getActiveCodec(): String = rtpPcmAudioTap?.codec ?: "pcm_s16le"
}
