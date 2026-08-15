package org.michimusic.link.rtp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.ReceiverSessionEffectiveDto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class RtpAudioSenderTest {

    @Test
    fun `buildRtpPacket generates valid RFC 3550 packet`() {
        val sender = RtpAudioSender()
        val payload = ByteArray(960) { (it % 128).toByte() }
        val seq = 100
        val ts = 48000L
        val pt = 97
        val ssrc = 12345678L

        val packetBytes = sender.buildRtpPacket(
            pcmPayload = payload,
            seq = seq,
            ts = ts,
            pt = pt,
            syncSrc = ssrc
        )

        assertEquals(12 + payload.size, packetBytes.size)

        val bb = ByteBuffer.wrap(packetBytes).order(ByteOrder.BIG_ENDIAN)
        val vPxCc = bb.get().toInt() and 0xFF
        val mPt = bb.get().toInt() and 0xFF
        val parsedSeq = bb.short.toInt() and 0xFFFF
        val parsedTs = bb.int.toLong() and 0xFFFFFFFFL
        val parsedSsrc = bb.int.toLong() and 0xFFFFFFFFL

        assertEquals("Version debe ser 2", 2, (vPxCc ushr 6) and 0x03)
        assertEquals("Payload type debe ser 97", pt, mPt and 0x7F)
        assertEquals("Sequence number coincide", seq, parsedSeq)
        assertEquals("Timestamp coincide", ts, parsedTs)
        assertEquals("SSRC coincide", ssrc, parsedSsrc)

        val readPayload = ByteArray(payload.size)
        bb.get(readPayload)
        assertTrue("Payload coincide", payload.contentEquals(readPayload))
    }

    @Test
    fun `stream multi packet sequence with strict monotonic sequence numbers and timestamps`() = runTest {
        val receiverSocket = DatagramSocket()
        val receiverPort = receiverSocket.localPort

        val effective = ReceiverSessionEffectiveDto(
            transport = "rtp_udp",
            codec = "pcm_s16le",
            sampleRate = 48000,
            bitDepth = 16,
            channels = 2,
            packetMs = 10,
            bufferMs = 120,
            payloadType = 97,
            ssrc = 987654321L,
            streamPort = receiverPort,
            volume = 70
        )

        val sender = RtpAudioSender()
        sender.start("127.0.0.1", effective, this)
        assertTrue(sender.isActive)

        // 10ms at 48000Hz, 16-bit, stereo = 480 samples * 2 channels * 2 bytes = 1920 bytes per packet.
        // Send 10 packets worth of audio (19200 bytes) in a single chunk to verify fragmentation.
        val totalPackets = 10
        val testChunk = ByteArray(1920 * totalPackets) { (it % 256).toByte() }
        sender.sendPcmChunk(testChunk)

        val buffer = ByteArray(2048)
        val receivedPacket = DatagramPacket(buffer, buffer.size)
        receiverSocket.soTimeout = 3000

        var lastSeq = -1
        var lastTs = -1L

        for (i in 0 until totalPackets) {
            receiverSocket.receive(receivedPacket)
            assertEquals(12 + 1920, receivedPacket.length)

            val bb = ByteBuffer.wrap(receivedPacket.data, 0, receivedPacket.length).order(ByteOrder.BIG_ENDIAN)
            val v = (bb.get().toInt() and 0xFF) ushr 6
            val pt = bb.get().toInt() and 0x7F
            val seq = bb.short.toInt() and 0xFFFF
            val ts = bb.int.toLong() and 0xFFFFFFFFL
            val ssrc = bb.int.toLong() and 0xFFFFFFFFL

            assertEquals(2, v)
            assertEquals(97, pt)
            assertEquals(effective.ssrc, ssrc)

            if (lastSeq != -1) {
                val expectedSeq = (lastSeq + 1) and 0xFFFF
                assertEquals("Sequence number must increment monotonically", expectedSeq, seq)
                val expectedTs = (lastTs + 480L) and 0xFFFFFFFFL
                assertEquals("Timestamp must increment exactly by samplesPerPacket (480)", expectedTs, ts)
            }

            lastSeq = seq
            lastTs = ts
        }

        sender.stop()
        assertFalse(sender.isActive)
        receiverSocket.close()
    }

    @Test
    fun `socket error triggers onError callback`() = runTest {
        val receiverSocket = DatagramSocket()
        val receiverPort = receiverSocket.localPort

        val effective = ReceiverSessionEffectiveDto(
            transport = "rtp_udp",
            codec = "pcm_s16le",
            sampleRate = 48000,
            bitDepth = 16,
            channels = 2,
            packetMs = 10,
            bufferMs = 120,
            payloadType = 97,
            ssrc = 11223344L,
            streamPort = receiverPort,
            volume = 70
        )

        var errorReported: Exception? = null
        val sender = RtpAudioSender()
        sender.onError = { errorReported = it }

        sender.start("127.0.0.1", effective, this)
        // Close receiver socket and close sender underlying socket forcefully to simulate network failure
        sender.stop()

        // Sending when stopped should be a no-op
        sender.sendPcmChunk(ByteArray(1920))
        assertFalse(sender.isActive)
        receiverSocket.close()
    }
}
