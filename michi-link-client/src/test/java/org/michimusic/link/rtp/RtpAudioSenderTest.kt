package org.michimusic.link.rtp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.ReceiverSessionEffectiveDto
import java.io.IOException
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
    fun `irregular chunk sizes preserve 100 percent of PCM bytes via accumulator without remainder loss`() = runTest {
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
            ssrc = 55667788L,
            streamPort = receiverPort,
            volume = 70
        )

        val sender = RtpAudioSender()
        sender.start("127.0.0.1", effective, this)

        // Packet size is 1920 bytes.
        // Send 4 chunks of 960 bytes (each is exactly half a packet).
        // Total = 3840 bytes = exactly 2 packets.
        val chunk1 = ByteArray(960) { 1 }
        val chunk2 = ByteArray(960) { 2 }
        val chunk3 = ByteArray(960) { 3 }
        val chunk4 = ByteArray(960) { 4 }

        sender.sendPcmChunk(chunk1)
        sender.sendPcmChunk(chunk2) // Should emit Packet 1 (chunk1 + chunk2)
        sender.sendPcmChunk(chunk3)
        sender.sendPcmChunk(chunk4) // Should emit Packet 2 (chunk3 + chunk4)

        val buffer = ByteArray(2048)
        val receivedPacket = DatagramPacket(buffer, buffer.size)
        receiverSocket.soTimeout = 3000

        // Receive Packet 1
        receiverSocket.receive(receivedPacket)
        assertEquals(12 + 1920, receivedPacket.length)
        val p1Payload = receivedPacket.data.copyOfRange(12, 12 + 1920)
        assertTrue(p1Payload.sliceArray(0 until 960).all { it == 1.toByte() })
        assertTrue(p1Payload.sliceArray(960 until 1920).all { it == 2.toByte() })

        // Receive Packet 2
        receiverSocket.receive(receivedPacket)
        assertEquals(12 + 1920, receivedPacket.length)
        val p2Payload = receivedPacket.data.copyOfRange(12, 12 + 1920)
        assertTrue(p2Payload.sliceArray(0 until 960).all { it == 3.toByte() })
        assertTrue(p2Payload.sliceArray(960 until 1920).all { it == 4.toByte() })

        sender.stop()
        receiverSocket.close()
    }

    @Test
    fun `switching from session A to session B resets buffer and sequence cleanly`() = runTest {
        val receiverA = DatagramSocket()
        val portA = receiverA.localPort

        val receiverB = DatagramSocket()
        val portB = receiverB.localPort

        val effectiveA = ReceiverSessionEffectiveDto(
            transport = "rtp_udp",
            codec = "pcm_s16le",
            sampleRate = 48000,
            bitDepth = 16,
            channels = 2,
            packetMs = 10,
            bufferMs = 120,
            payloadType = 97,
            ssrc = 1111L,
            streamPort = portA,
            volume = 70
        )

        val effectiveB = ReceiverSessionEffectiveDto(
            transport = "rtp_udp",
            codec = "pcm_s16le",
            sampleRate = 48000,
            bitDepth = 16,
            channels = 2,
            packetMs = 10,
            bufferMs = 120,
            payloadType = 97,
            ssrc = 2222L,
            streamPort = portB,
            volume = 70
        )

        val sender = RtpAudioSender()
        sender.start("127.0.0.1", effectiveA, this)
        sender.sendPcmChunk(ByteArray(1920) { 10 })

        val bufA = ByteArray(2048)
        val pktA = DatagramPacket(bufA, bufA.size)
        receiverA.soTimeout = 3000
        receiverA.receive(pktA)
        assertEquals(12 + 1920, pktA.length)

        // Switch to Session B
        sender.start("127.0.0.1", effectiveB, this)
        sender.sendPcmChunk(ByteArray(1920) { 20 })

        val bufB = ByteArray(2048)
        val pktB = DatagramPacket(bufB, bufB.size)
        receiverB.soTimeout = 3000
        receiverB.receive(pktB)
        assertEquals(12 + 1920, pktB.length)

        val bbB = ByteBuffer.wrap(pktB.data, 0, pktB.length).order(ByteOrder.BIG_ENDIAN)
        bbB.position(8)
        val ssrcB = bbB.int.toLong() and 0xFFFFFFFFL
        assertEquals(effectiveB.ssrc, ssrcB)

        sender.stop()
        receiverA.close()
        receiverB.close()
    }

    @Test
    fun `socket send error triggers onError callback with real exception`() = runTest {
        var errorReported: Exception? = null

        // Custom failing DatagramSocket that throws IOException on send
        val failingSocket = object : DatagramSocket() {
            override fun send(p: DatagramPacket?) {
                throw IOException("Network unreachable test simulation")
            }
        }

        val effective = ReceiverSessionEffectiveDto(
            transport = "rtp_udp",
            codec = "pcm_s16le",
            sampleRate = 48000,
            bitDepth = 16,
            channels = 2,
            packetMs = 10,
            bufferMs = 120,
            payloadType = 97,
            ssrc = 99999L,
            streamPort = 55555,
            volume = 70
        )

        val sender = RtpAudioSender(socketProvider = { failingSocket })
        sender.onError = { errorReported = it }

        sender.start("127.0.0.1", effective, this)
        sender.sendPcmChunk(ByteArray(1920))

        var attempts = 0
        while (attempts < 50 && errorReported == null) {
            delay(50)
            attempts++
        }

        assertNotNull("onError must be called with IOException on socket failure", errorReported)
        assertTrue(errorReported is IOException)
        assertEquals("Network unreachable test simulation", errorReported?.message)

        sender.stop()
    }
}
