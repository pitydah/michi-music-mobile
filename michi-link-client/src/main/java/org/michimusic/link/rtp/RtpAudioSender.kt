package org.michimusic.link.rtp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.michimusic.link.dto.ReceiverSessionEffectiveDto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encapsulates PCM audio into RFC 3550 RTP packets and transmits them via UDP
 * to a Michi Stream receiver device.
 */
class RtpAudioSender(
    private val socketProvider: () -> DatagramSocket = { DatagramSocket() }
) {
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0
    private var ssrc: Long = 0L
    private var payloadType: Int = 97
    private var sampleRate: Int = 48000
    private var channels: Int = 2
    private var bitDepth: Int = 16
    private var packetMs: Int = 10
    
    private var sequenceNumber: Int = 0
    private var timestamp: Long = 0L
    private var isStreaming = false

    private val audioChannel = Channel<ByteArray>(capacity = 64)
    private var senderJob: Job? = null

    val isActive: Boolean get() = isStreaming

    fun start(
        targetHost: String,
        effective: ReceiverSessionEffectiveDto,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    ) {
        stop()
        targetAddress = InetAddress.getByName(targetHost)
        targetPort = effective.streamPort
        ssrc = effective.ssrc
        payloadType = effective.payloadType
        sampleRate = effective.sampleRate
        channels = effective.channels
        bitDepth = effective.bitDepth
        packetMs = effective.packetMs

        sequenceNumber = (0..65535).random()
        timestamp = (0L..4294967295L).random()
        socket = socketProvider()
        isStreaming = true

        val packetSizeBytes = (sampleRate * packetMs / 1000) * channels * (bitDepth / 8)
        val samplesPerPacket = sampleRate * packetMs / 1000

        senderJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocate(12 + packetSizeBytes).order(ByteOrder.BIG_ENDIAN)
            while (isActive && isStreaming) {
                val pcmChunk = audioChannel.receiveCatching().getOrNull() ?: break
                
                var offset = 0
                while (offset + packetSizeBytes <= pcmChunk.size && isStreaming) {
                    buffer.clear()
                    // 1. RTP Header (12 bytes)
                    // Byte 0: V=2, P=0, X=0, CC=0 -> 0x80
                    buffer.put(0x80.toByte())
                    // Byte 1: M=0, PT=payloadType -> payloadType & 0x7F
                    buffer.put((payloadType and 0x7F).toByte())
                    // Bytes 2-3: Sequence Number (16 bits)
                    buffer.putShort((sequenceNumber and 0xFFFF).toShort())
                    // Bytes 4-7: Timestamp (32 bits)
                    buffer.putInt((timestamp and 0xFFFFFFFFL).toInt())
                    // Bytes 8-11: SSRC (32 bits)
                    buffer.putInt((ssrc and 0xFFFFFFFFL).toInt())

                    // 2. PCM Payload
                    buffer.put(pcmChunk, offset, packetSizeBytes)

                    val packetData = buffer.array()
                    val packet = DatagramPacket(packetData, packetData.size, targetAddress, targetPort)
                    try {
                        socket?.send(packet)
                    } catch (e: Exception) {
                        // Socket closed or network error
                        break
                    }

                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    timestamp = (timestamp + samplesPerPacket) and 0xFFFFFFFFL
                    offset += packetSizeBytes
                }
            }
        }
    }

    /**
     * Queues PCM data for RTP transmission.
     */
    fun sendPcmChunk(pcmData: ByteArray) {
        if (!isStreaming) return
        audioChannel.trySend(pcmData)
    }

    /**
     * Builds a single standalone RTP packet buffer (useful for direct transmission and unit testing).
     */
    fun buildRtpPacket(
        pcmPayload: ByteArray,
        seq: Int = sequenceNumber,
        ts: Long = timestamp,
        pt: Int = payloadType,
        syncSrc: Long = ssrc
    ): ByteArray {
        val buffer = ByteBuffer.allocate(12 + pcmPayload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x80.toByte())
        buffer.put((pt and 0x7F).toByte())
        buffer.putShort((seq and 0xFFFF).toShort())
        buffer.putInt((ts and 0xFFFFFFFFL).toInt())
        buffer.putInt((syncSrc and 0xFFFFFFFFL).toInt())
        buffer.put(pcmPayload)
        return buffer.array()
    }

    fun stop() {
        isStreaming = false
        senderJob?.cancel()
        senderJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}
