package org.michimusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RtpPcmAudioTapTest {

    @Test
    fun `initial currentFormat is null (UNKNOWN state) and resets cleanly`() {
        val tap = RtpPcmAudioTap()
        assertNull("Tap initial format must be null/UNKNOWN until onConfigure is called", tap.activeFormat)
        assertNull("Tap currentFormat StateFlow value must be null initially", tap.currentFormat.value)

        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format)

        val active = tap.activeFormat
        assertNotNull("Tap format must be configured", active)
        assertEquals(44100, active!!.sampleRate)
        assertEquals(2, active.channels)
        assertEquals(16, active.bitDepth)
        assertEquals("pcm_s16le", active.codec)

        tap.reset()
        assertNull("Tap format must reset to null on reset()", tap.activeFormat)
    }

    @Test
    fun `changing format locks emission atomically via isRenegotiating until resumed`() {
        val tap = RtpPcmAudioTap()
        val format44 = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format44)

        var emissionCount = 0
        tap.pcmChunkListener = { emissionCount++ }
        tap.isEnabled = true

        val inputData = byteArrayOf(1, 2, 3, 4)
        val buf1 = ByteBuffer.allocateDirect(inputData.size).order(ByteOrder.nativeOrder())
        buf1.put(inputData)
        buf1.flip()
        tap.queueInput(buf1)

        assertEquals("First chunk under 44.1kHz must be delivered", 1, emissionCount)
        assertFalse(tap.isRenegotiating)

        // Change format to 48kHz (simulating track change in Media3)
        val format48 = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format48)

        assertTrue("isRenegotiating must be true immediately on format change", tap.isRenegotiating)

        // Queue input buffer while isRenegotiating is true
        val buf2 = ByteBuffer.allocateDirect(inputData.size).order(ByteOrder.nativeOrder())
        buf2.put(inputData)
        buf2.flip()
        tap.queueInput(buf2)

        assertEquals("Zero bytes of new 48kHz format must reach old listener while isRenegotiating is true", 1, emissionCount)

        // Resume streaming (simulating new RTP sender is ready)
        tap.isRenegotiating = false
        val buf3 = ByteBuffer.allocateDirect(inputData.size).order(ByteOrder.nativeOrder())
        buf3.put(inputData)
        buf3.flip()
        tap.queueInput(buf3)

        assertEquals("Delivery must resume after isRenegotiating is cleared", 2, emissionCount)
    }

    @Test
    fun `changing channels or bit depth locks emission atomically via isRenegotiating until resumed`() {
        val tap = RtpPcmAudioTap()
        val format16Stereo = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format16Stereo)

        var emissionCount = 0
        tap.pcmChunkListener = { emissionCount++ }
        tap.isEnabled = true

        val inputData16 = byteArrayOf(1, 2, 3, 4)
        val buf1 = ByteBuffer.allocateDirect(inputData16.size).order(ByteOrder.nativeOrder())
        buf1.put(inputData16)
        buf1.flip()
        tap.queueInput(buf1)

        assertEquals(1, emissionCount)

        // Change to 24-bit PCM
        val format24Stereo = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_24BIT)
        tap.configure(format24Stereo)

        assertTrue("isRenegotiating must be true on bit-depth change", tap.isRenegotiating)
        assertEquals(24, tap.activeFormat?.bitDepth)

        val inputData24 = byteArrayOf(1, 2, 3, 4, 5, 6)
        val buf2 = ByteBuffer.allocateDirect(inputData24.size).order(ByteOrder.nativeOrder())
        buf2.put(inputData24)
        buf2.flip()
        tap.queueInput(buf2)

        assertEquals("No 24-bit data should reach 16-bit listener during renegotiation", 1, emissionCount)

        tap.isRenegotiating = false
        val buf3 = ByteBuffer.allocateDirect(inputData24.size).order(ByteOrder.nativeOrder())
        buf3.put(inputData24)
        buf3.flip()
        tap.queueInput(buf3)

        assertEquals(2, emissionCount)
    }

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
