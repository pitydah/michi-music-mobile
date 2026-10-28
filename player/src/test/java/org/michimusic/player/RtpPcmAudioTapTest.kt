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

    @Test
    fun `sender exception stays inside audio tap and latches a fault`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        tap.isEnabled = true
        tap.muteLocalOutput = true
        var count = 0
        tap.pcmChunkListener = { count++; throw IllegalStateException("receiver offline") }

        tap.queueInput(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))
        assertTrue(tap.hasDeliveryFault)
        assertTrue(tap.isPaused)
        assertEquals(1, count)
        tap.queueInput(ByteBuffer.wrap(byteArrayOf(5, 6, 7, 8)))
        assertEquals("Delivery must remain suspended until explicit recovery", 1, count)
        val output = tap.output
        val pcm = ByteArray(output.remaining())
        output.get(pcm)
        assertTrue("A remote-only output must remain silent on the handset", pcm.contentEquals(byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun `new streaming connection can explicitly reset the fault latch`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        tap.isEnabled = true
        tap.pcmChunkListener = { throw IllegalStateException("network down") }
        tap.queueInput(ByteBuffer.wrap(byteArrayOf(1, 2)))
        assertTrue(tap.hasDeliveryFault)
        tap.resetDeliveryFault()
        tap.isPaused = false
        var delivered = false
        tap.pcmChunkListener = { delivered = true }
        tap.queueInput(ByteBuffer.wrap(byteArrayOf(3, 4)))
        assertFalse(tap.hasDeliveryFault)
        assertTrue(delivered)
    }

    @Test
    fun `format first seen while sender enabled must be renegotiated`() {
        val tap = RtpPcmAudioTap()
        tap.isEnabled = true
        tap.configure(AudioProcessor.AudioFormat(96000, 2, C.ENCODING_PCM_24BIT))
        assertTrue(tap.isRenegotiating)
        var count = 0
        tap.pcmChunkListener = { count++ }
        tap.queueInput(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertEquals(0, count)
    }

    @Test
    fun `Media3 reset while remote sender is enabled invalidates format negotiation`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        tap.isEnabled = true
        tap.reset()
        assertNull(tap.activeFormat)
        assertTrue(tap.isRenegotiating)
    }

    @Test
    fun `float PCM encoding is not mislabeled as integer 32 bit`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(96000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals("pcm_f32le", tap.activeFormat?.codec)
        assertEquals(32, tap.activeFormat?.bitDepth)
    }

    @Test
    fun `callback from previous sender cannot poison replacement connection`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val obsolete = Thread {
            tap.queueInput(ByteBuffer.wrap(byteArrayOf(1, 2)))
        }
        tap.startSender {
            entered.countDown()
            check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            throw IllegalStateException("stale sender failed after replacement")
        }
        obsolete.start()
        assertTrue("Old callback never started", entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        var received = false
        tap.startSender { received = true }
        release.countDown()
        obsolete.join(3_000)
        assertFalse("Old sender fault must not affect new sender", tap.hasDeliveryFault)
        assertFalse(tap.isPaused)
        tap.queueInput(ByteBuffer.wrap(byteArrayOf(3, 4)))
        assertTrue(received)
    }

    @Test
    fun `stop disconnects old callback without reactivating it`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        tap.startSender { throw IllegalStateException("broken") }
        tap.stopSender()
        assertFalse(tap.isEnabled)
        assertNull(tap.pcmChunkListener)
        assertFalse(tap.muteLocalOutput)
    }

    @Test
    fun `asynchronous sender failure only latches its own active listener`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        val old: (ByteArray) -> Unit = {}
        val replacement: (ByteArray) -> Unit = {}
        tap.startSender(old)
        tap.startSender(replacement)
        tap.reportSenderFailureIfCurrent(old)
        assertFalse("Stale worker cannot poison new sender", tap.hasDeliveryFault)
        tap.reportSenderFailureIfCurrent(replacement)
        assertTrue(tap.hasDeliveryFault)
        assertTrue(tap.isPaused)
        assertTrue(tap.muteLocalOutput)
    }

    @Test
    fun `format discontinuity drains registered network buffer`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        var cleared = 0
        tap.onFormatDiscontinuity = { cleared++ }
        tap.startSender { }
        tap.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(1, cleared)
        assertTrue(tap.isRenegotiating)
        tap.reset()
        assertEquals(2, cleared)
    }

    @Test
    fun `reset and reconfigure with identical format recovers via completeRenegotiation`() {
        val tap = RtpPcmAudioTap()
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format)

        var chunksReceived = 0
        tap.startSender { chunksReceived++ }
        assertEquals(44100, tap.activeFormat?.sampleRate)

        // Queue a chunk
        val sample = byteArrayOf(1, 2, 3, 4)
        val buf1 = ByteBuffer.allocateDirect(sample.size).order(ByteOrder.nativeOrder())
        buf1.put(sample)
        buf1.flip()
        tap.queueInput(buf1)
        assertEquals(1, chunksReceived)

        // Media3 processor resets (seek, buffer underrun or track transition)
        tap.reset()
        assertTrue(tap.isRenegotiating)

        // Reconfigure with IDENTICAL format
        tap.configure(format)
        assertTrue(tap.isRenegotiating)

        // If unhandled, chunks would be dropped. But caller invokes completeRenegotiation():
        tap.completeRenegotiation(checkNotNull(tap.activeFormat))
        assertFalse(tap.isRenegotiating)

        val buf2 = ByteBuffer.allocateDirect(sample.size).order(ByteOrder.nativeOrder())
        buf2.put(sample)
        buf2.flip()
        tap.queueInput(buf2)
        assertEquals("Chunks must resume delivery after identical format recovery", 2, chunksReceived)
    }

    @Test
    fun `reset and reconfigure with different format keeps isRenegotiating locked until explicitly acknowledged`() {
        val tap = RtpPcmAudioTap()
        val format44 = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format44)

        var chunksReceived = 0
        tap.startSender { chunksReceived++ }

        tap.reset()
        val format48 = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        tap.configure(format48)

        // Stale format acknowledgement is ignored
        tap.completeRenegotiation(org.michimusic.core.models.PcmFormat(44100, 2, 16, "pcm_s16le"))
        assertTrue("Renegotiation must remain locked if format mismatch", tap.isRenegotiating)

        // Acknowledge matching format
        tap.completeRenegotiation(checkNotNull(tap.activeFormat))
        assertFalse(tap.isRenegotiating)
    }

    @Test
    fun `disconnection stops sender and clears renegotiation locks`() {
        val tap = RtpPcmAudioTap()
        tap.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        tap.startSender { }
        tap.reset()
        assertTrue(tap.isRenegotiating)

        // Disconnect
        tap.stopSender()
        assertFalse(tap.isEnabled)
        assertFalse(tap.isRenegotiating)
        assertNull(tap.pcmChunkListener)
    }
}
