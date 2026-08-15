package org.michimusic.link.rtp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.ReceiverSessionEffectiveDto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    fun `start and transmit packets via local UDP socket`() = runTest {
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

        // 10ms of 48000Hz 16-bit 2ch is 480 * 2 * 2 = 1920 bytes
        val testChunk = ByteArray(1920) { (it % 100).toByte() }
        sender.sendPcmChunk(testChunk)

        val buffer = ByteArray(2048)
        val receivedPacket = DatagramPacket(buffer, buffer.size)
        receiverSocket.soTimeout = 3000
        receiverSocket.receive(receivedPacket)

        assertEquals(12 + 1920, receivedPacket.length)
        val bb = ByteBuffer.wrap(receivedPacket.data, 0, receivedPacket.length).order(ByteOrder.BIG_ENDIAN)
        val v = (bb.get().toInt() and 0xFF) ushr 6
        val pt = bb.get().toInt() and 0x7F
        assertEquals(2, v)
        assertEquals(97, pt)

        sender.stop()
        receiverSocket.close()
    }
}
