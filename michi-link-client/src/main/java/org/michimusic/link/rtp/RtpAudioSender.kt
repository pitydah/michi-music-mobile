package org.michimusic.link.rtp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.michimusic.link.dto.ReceiverSessionEffectiveDto
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnostic metrics snapshot for RTP audio streaming.
 */
data class RtpMetrics(
    val packetsSent: Long,
    val pcmBytesReceived: Long,
    val pcmBytesDropped: Long,
    val bufferOverflowsTotal: Long,
    val consecutiveOverflows: Int,
    val socketErrors: Long,
    val isActive: Boolean,
)

/**
 * Encapsulates PCM audio into RFC 3550 RTP packets and transmits them via UDP
 * to a Michi Stream receiver device.
 */
class RtpAudioSender(
    private val socketProvider: () -> DatagramSocket = { DatagramSocket() }
) {
    companion object {
        const val MAX_SUSTAINED_OVERFLOWS = 50
    }

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

    private var audioChannel: Channel<ByteArray>? = null
    private var senderJob: Job? = null

    var onError: ((Exception) -> Unit)? = null

    var packetsSent: Long = 0
        private set
    var pcmBytesReceived: Long = 0
        private set
    var pcmBytesDropped: Long = 0
        private set
    var bufferOverflowsTotal: Long = 0
        private set
    var consecutiveOverflows: Int = 0
        private set
    var socketErrors: Long = 0
        private set

    val isActive: Boolean get() = isStreaming

    fun getMetrics(): RtpMetrics = RtpMetrics(
        packetsSent = packetsSent,
        pcmBytesReceived = pcmBytesReceived,
        pcmBytesDropped = pcmBytesDropped,
        bufferOverflowsTotal = bufferOverflowsTotal,
        consecutiveOverflows = consecutiveOverflows,
        socketErrors = socketErrors,
        isActive = isStreaming,
    )

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

        packetsSent = 0
        pcmBytesReceived = 0
        pcmBytesDropped = 0
        bufferOverflowsTotal = 0
        consecutiveOverflows = 0
        socketErrors = 0

        val channel = Channel<ByteArray>(capacity = 128)
        audioChannel = channel

        val packetSizeBytes = (sampleRate * packetMs / 1000) * channels * (bitDepth / 8)
        val samplesPerPacket = sampleRate * packetMs / 1000

        senderJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocate(12 + packetSizeBytes).order(ByteOrder.BIG_ENDIAN)
            val accumulator = ByteArrayOutputStream()

            while (isActive && isStreaming) {
                val pcmChunk = channel.receiveCatching().getOrNull() ?: break
                accumulator.write(pcmChunk)

                val currentBytes = accumulator.toByteArray()
                var offset = 0

                while (offset + packetSizeBytes <= currentBytes.size && isStreaming) {
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
                    buffer.put(currentBytes, offset, packetSizeBytes)

                    val packetData = buffer.array()
                    val packet = DatagramPacket(packetData, packetData.size, targetAddress, targetPort)
                    try {
                        socket?.send(packet)
                        packetsSent++
                    } catch (e: Exception) {
                        socketErrors++
                        onError?.invoke(e)
                        break
                    }

                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    timestamp = (timestamp + samplesPerPacket) and 0xFFFFFFFFL
                    offset += packetSizeBytes
                }

                // Preserve remainder in accumulator for next chunk
                accumulator.reset()
                if (offset < currentBytes.size) {
                    accumulator.write(currentBytes, offset, currentBytes.size - offset)
                }
            }
        }
    }

    /**
     * Queues PCM data for RTP transmission.
     */
    fun sendPcmChunk(pcmData: ByteArray) {
        if (!isStreaming) return
        val ch = audioChannel ?: return
        pcmBytesReceived += pcmData.size

        val result = ch.trySend(pcmData)
        if (result.isSuccess) {
            consecutiveOverflows = 0
        } else {
            bufferOverflowsTotal++
            consecutiveOverflows++
            pcmBytesDropped += pcmData.size

            if (consecutiveOverflows >= MAX_SUSTAINED_OVERFLOWS) {
                onError?.invoke(IllegalStateException("Sustained RTP buffer overflow ($consecutiveOverflows consecutive drops): network socket clogged"))
            }
        }
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
        audioChannel?.close()
        audioChannel = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}
