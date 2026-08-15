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
    }

    @JvmStatic
    fun stopPcmStreaming() {
        val tap = rtpPcmAudioTap ?: return
        tap.isEnabled = false
        tap.isPaused = false
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
}
