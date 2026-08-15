package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RtpPcmAudioTapTest {

    @Test
    fun `tap forwards pcm chunks to listener when enabled`() {
        val tap = RtpPcmAudioTap()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        val configured = tap.configure(format)
        assertEquals(format, configured)

        var receivedBytes: ByteArray? = null
        tap.pcmChunkListener = { receivedBytes = it }
        tap.isEnabled = true

        val inputData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val inputBuffer = ByteBuffer.allocateDirect(inputData.size).order(ByteOrder.nativeOrder())
        inputBuffer.put(inputData)
        inputBuffer.flip()

        tap.queueInput(inputBuffer)

        assertTrue(receivedBytes != null)
        assertTrue(inputData.contentEquals(receivedBytes!!))

        val outputBuffer = tap.output
        val outBytes = ByteArray(outputBuffer.remaining())
        outputBuffer.get(outBytes)
        assertTrue(inputData.contentEquals(outBytes))
    }

    @Test
    fun `tap ignores pcm chunks when disabled`() {
        val tap = RtpPcmAudioTap()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format)

        var received = false
        tap.pcmChunkListener = { received = true }
        tap.isEnabled = false

        val inputData = byteArrayOf(10, 20, 30, 40)
        val inputBuffer = ByteBuffer.allocateDirect(inputData.size).order(ByteOrder.nativeOrder())
        inputBuffer.put(inputData)
        inputBuffer.flip()

        tap.queueInput(inputBuffer)

        assertFalse("No debe emitir cuando isEnabled es false", received)
    }

    @Test
    fun `tap produces silence downstream when muteLocalOutput is true`() {
        val tap = RtpPcmAudioTap()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format)

        var receivedBytes: ByteArray? = null
        tap.pcmChunkListener = { receivedBytes = it }
        tap.isEnabled = true
        tap.muteLocalOutput = true

        val inputData = byteArrayOf(1, 2, 3, 4)
        val inputBuffer = ByteBuffer.allocateDirect(inputData.size).order(ByteOrder.nativeOrder())
        inputBuffer.put(inputData)
        inputBuffer.flip()

        tap.queueInput(inputBuffer)

        assertTrue("El listener recibe los bytes reales", inputData.contentEquals(receivedBytes!!))

        val outputBuffer = tap.output
        val outBytes = ByteArray(outputBuffer.remaining())
        outputBuffer.get(outBytes)
        val expectedSilence = byteArrayOf(0, 0, 0, 0)
        assertTrue("La salida local debe ser silencio cuando muteLocalOutput es true", expectedSilence.contentEquals(outBytes))
    }
}
